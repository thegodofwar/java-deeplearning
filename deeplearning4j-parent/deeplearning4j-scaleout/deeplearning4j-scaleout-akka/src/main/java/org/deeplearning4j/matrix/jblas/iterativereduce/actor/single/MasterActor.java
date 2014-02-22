package org.deeplearning4j.matrix.jblas.iterativereduce.actor.single;

import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.random.RandomGenerator;
import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.matrix.jblas.iterativereduce.actor.core.ResetMessage;
import org.deeplearning4j.matrix.jblas.iterativereduce.actor.core.UpdateMessage;
import org.deeplearning4j.matrix.jblas.iterativereduce.actor.core.api.EpochDoneListener;
import org.deeplearning4j.nn.BaseNeuralNetwork;
import org.deeplearning4j.scaleout.conf.Conf;
import org.deeplearning4j.scaleout.iterativereduce.single.UpdateableSingleImpl;
import org.jblas.DoubleMatrix;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.cluster.Cluster;
import akka.contrib.pattern.DistributedPubSubMediator;
import akka.japi.Creator;


/**
 * Handles a set of workers and acts as a parameter server for iterative reduce
 * @author Adam Gibson
 *
 */
public class MasterActor extends org.deeplearning4j.matrix.jblas.iterativereduce.actor.core.actor.MasterActor<UpdateableSingleImpl> {



	/**
	 * Creates the master and the workers with this given conf
	 * @param conf the neural net config to use
	 */
	public MasterActor(Conf conf,ActorRef batchActor) {
		super(conf,batchActor);
	}

	public static Props propsFor(Conf conf,ActorRef batchActor) {
		return Props.create(new MasterActor.MasterActorFactory(conf,batchActor));
	}



	@Override
	public UpdateableSingleImpl compute(Collection<UpdateableSingleImpl> workerUpdates,
			Collection<UpdateableSingleImpl> masterUpdates) {


		SingleNetworkAccumulator acc = new SingleNetworkAccumulator();
		for(UpdateableSingleImpl m : workerUpdates) 
			acc.accumulate(m.get());

		masterResults.set(acc.averaged());

		return masterResults;
	}



	@Override
	public void setup(Conf conf) {
		//use the rng with the given seed
		RandomGenerator rng =  new MersenneTwister(conf.getSeed());
		BaseNeuralNetwork matrix = new BaseNeuralNetwork.Builder<>()
				.withClazz((Class<? extends BaseNeuralNetwork>) conf.getNeuralNetworkClazz())
				.withRandom(rng).withL2(conf.getL2())
				.withMomentum(conf.getMomentum())
				.numberOfVisible(conf.getnIn())
				.numHidden(conf.getnOut())
				.build();
		masterResults = new UpdateableSingleImpl(matrix);

	}


	@SuppressWarnings({ "unchecked" })
	@Override
	public void onReceive(Object message) throws Exception {
		if (message instanceof DistributedPubSubMediator.SubscribeAck) {
			DistributedPubSubMediator.SubscribeAck ack = (DistributedPubSubMediator.SubscribeAck) message;
			log.info("Subscribed " + ack.toString());
		}
		else if(message instanceof EpochDoneListener) {
			listener = (EpochDoneListener<UpdateableSingleImpl>) message;
			log.info("Set listener");
		}

		else if(message instanceof UpdateableSingleImpl) {
			UpdateableSingleImpl up = (UpdateableSingleImpl) message;
			updates.add(up);
			if(updates.size() == partition) {
				masterResults = this.compute(updates, updates);
				if(listener != null)
					listener.epochComplete(masterResults);
				//reset the dataset

				if(epochsComplete == conf.getPretrainEpochs()) {
					isDone = true;
					batchActor.tell(up, getSelf());
					updates.clear();
					Cluster.get(this.getContext().system()).down(Cluster.get(getContext().system()).selfAddress());
					context().system().shutdown();
					log.info("Last iteration; left cluster");
				}
				else {
					batchActor.tell(new ResetMessage(), getSelf());
					epochsComplete++;
					batchActor.tell(up, getSelf());
					updates.clear();
				}
				


			}

		}

		//broadcast new weights to workers
		else if(message instanceof UpdateMessage) {
			mediator.tell(new DistributedPubSubMediator.Publish(BROADCAST,
					message), getSelf());
		}


		//list of examples
		else if(message instanceof List || message instanceof Pair) {

			if(message instanceof List) {
				List<Pair<DoubleMatrix,DoubleMatrix>> list = (List<Pair<DoubleMatrix,DoubleMatrix>>) message;
				//each pair in the matrix pairs maybe multiple rows
				splitListIntoRows(list);
				//delegate split to workers
				sendToWorkers(list);

			}

			//ensure split then send to workers
			else if(message instanceof Pair) {
				Pair<DoubleMatrix,DoubleMatrix> pair = (Pair<DoubleMatrix,DoubleMatrix>) message;

				//split pair up in to rows to ensure parallelism
				List<DoubleMatrix> inputs = pair.getFirst().rowsAsList();
				List<DoubleMatrix> labels = pair.getSecond().rowsAsList();

				List<Pair<DoubleMatrix,DoubleMatrix>> pairs = new ArrayList<>();
				for(int i = 0; i < inputs.size(); i++) {
					pairs.add(new Pair<>(inputs.get(i),labels.get(i)));
				}


				sendToWorkers(pairs);

			}
		}

		else
			unhandled(message);
	}







	public static class MasterActorFactory implements Creator<MasterActor> {

		public MasterActorFactory(Conf conf,ActorRef batchActor) {
			this.conf = conf;
			this.batchActor = batchActor;
		}

		private Conf conf;
		private ActorRef batchActor;
		/**
		 * 
		 */
		private static final long serialVersionUID = 1932205634961409897L;

		@Override
		public MasterActor create() throws Exception {
			return new MasterActor(conf,batchActor);
		}



	}


	@Override
	public void complete(DataOutputStream ds) {
		this.masterResults.get().write(ds);
	}



}