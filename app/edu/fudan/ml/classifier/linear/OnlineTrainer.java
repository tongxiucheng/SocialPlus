package edu.fudan.ml.classifier.linear;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import edu.fudan.ml.classifier.Predict;
import edu.fudan.ml.classifier.linear.inf.Inferencer;
import edu.fudan.ml.classifier.linear.inf.LinearMax;
import edu.fudan.ml.classifier.linear.update.LinearMaxPAUpdate;
import edu.fudan.ml.classifier.linear.update.Update;
import edu.fudan.ml.feature.Generator;
import edu.fudan.ml.feature.SFGenerator;
import edu.fudan.ml.loss.Loss;
import edu.fudan.ml.loss.ZeroOneLoss;
import edu.fudan.ml.types.Instance;
import edu.fudan.ml.types.InstanceSet;
import edu.fudan.ml.types.alphabet.AlphabetFactory;
import edu.fudan.util.MyArrays;

/**
 * 在线参数训练类，
 * 可能问题：收敛控制，参数c设置过小，可能会导致“假收敛”的情况 2012.8.6
 *
 */
public class OnlineTrainer extends AbstractTrainer {

	/**
	 * 收敛控制，保留最近的错误率个数
	 */
	private static final int historyNum = 5;
	/**
	 * 收敛控制，最小误差
	 */
	public static float eps = 1e-10f;

	public TrainMethod method = TrainMethod.FastAverage;

	public boolean DEBUG = false;
	public boolean shuffle = true;
	public boolean finalOptimized = false;
	public boolean innerOptimized = false;
	public boolean simpleOutput = false;
	public boolean interim = false;

	public float c=0.1f;

	public float threshold = 0.99f;

	protected Linear classifier;
	protected Inferencer inferencer;
	protected Loss loss;
	protected Update update;
	protected Random random;

	public int iternum;
	protected float[] weights;

	public enum TrainMethod {
		Perceptron, Average, FastAverage
	}
	public OnlineTrainer(AlphabetFactory af, int c) {
		//默认特征生成器
		Generator gen = new SFGenerator();
		//默认推理器
		this.inferencer = new LinearMax(gen, af.getLabelSize());
		//默认损失函数
		this.loss =  new ZeroOneLoss();
		//默认参数更新策略
		this.update = new LinearMaxPAUpdate(loss);
		this.iternum = c;
		this.c = 0.1f;
		weights = (float[]) inferencer.getWeights();
		if (weights == null) {
			weights = new float[af.getFeatureSize()];
			inferencer.setWeights(weights);
		}
		random = new Random(1l);
	}
	/**
	 * 构造函数
	 * @param af 字典
	 */
	public OnlineTrainer(AlphabetFactory af) {
		this(af,50);
	}

	/**
	 * 构造函数
	 * @param inferencer 推理算法
	 * @param update 参数更新方法
	 * @param loss 损失计算方法
	 * @param fsize 特征数量
	 * @param iternum 最大迭代次数
	 * @param c 步长阈值
	 */
	public OnlineTrainer(Inferencer inferencer, Update update,
			Loss loss, int fsize, int iternum, float c) {
		this.inferencer = inferencer;
		this.update = update;
		this.loss = loss;
		this.iternum = iternum;
		this.c = c;
		weights = (float[]) inferencer.getWeights();
		if (weights == null) {
			weights = new float[fsize];
			inferencer.setWeights(weights);
		}else if(weights.length<fsize){
			weights = Arrays.copyOf(weights, fsize);
			inferencer.setWeights(weights);
		}
		random = new Random(1l);
	}

	/**
	 * 构造函数，可根据已训练得到的模型重新开始训练
	 * @param classifier 分类器
	 * @param update 参数更新方法
	 * @param loss 损失计算方法
	 * @param fsize 特征数量
	 * @param iternum 最大迭代次数
	 * @param c 步长阈值
	 */
	public OnlineTrainer(Linear classifier, Update update, Loss loss, int fsize, int iternum, float c) {
		this(classifier.getInferencer(), update, loss, fsize, iternum, c);
	}

	/**
	 * 参数训练方法
	 * @return 线性分类器
	 */
	@Override
	public Linear train(InstanceSet trainset) {
		return train(trainset,null);
	}

	/**
	 * 参数训练方法
	 * @return 线性分类器
	 */
	@Override
	public Linear train(InstanceSet trainset, InstanceSet devset) {
		int numSamples = trainset.size();

		float[] hisErrRate = new float[historyNum];

		long beginTime, endTime;
		long beginTimeIter, endTimeIter;
		int iter = 0;
		int frac = numSamples / 10;

		float[] averageWeights = null;
		if (method == TrainMethod.Average || method == TrainMethod.FastAverage) {
			averageWeights = new float[weights.length];
		}

		beginTime = System.currentTimeMillis();

		if (shuffle)
			trainset.shuffle(random);

		while (iter++ < iternum) {
			if (!simpleOutput) {
				System.out.print("iter "+iter+":  ");
			}
			float err = 0;
			float errtot = 0;
			int cnt = 0;
			int cnttot = 0;
			int progress = frac;

			beginTimeIter = System.currentTimeMillis();

			float[] innerWeights = null;
			if (method == TrainMethod.Average) {
				innerWeights = Arrays.copyOf(weights, weights.length);
			}

			for (int ii = 0; ii < numSamples; ii++) {
				Instance inst = trainset.getInstance(ii);
				Predict pred = (Predict) inferencer.getBest(inst,2);				
				
				float l = loss.calc(pred.getLabel(0), inst.getTarget());
				if (l > 0) {
					err += l;
					errtot++;
					update.update(inst, weights, pred.getLabel(0), c);
					
				}else{
					if (pred.size() > 1)
						update.update(inst, weights, pred.getLabel(1), c);
				}
				cnt += inst.length();
				cnttot++;
				if (method == TrainMethod.Average) {
					for (int i = 0; i < weights.length; i++) {
						innerWeights[i] += weights[i];
					}
				}

				if (!simpleOutput && progress != 0 && ii % progress == 0) {
					System.out.print('.');
					progress += frac;
				}
			}

			float curErrRate = err / cnt;

			endTimeIter = System.currentTimeMillis();

			if (!simpleOutput) {
				System.out.println("  time: " + (endTimeIter - beginTimeIter)
						/ 1000.0 + "s");
				System.out.print("Train:");
				System.out.print("  Tag acc: ");
			}
			System.out.print(1 - curErrRate);
			if (!simpleOutput) {
				System.out.print("  Sentence acc: ");
				System.out.print(1 - errtot / cnttot);
				System.out.println();
			}

			System.out.print("Weight Numbers: "
					+ MyArrays.countNoneZero(weights));
			if (innerOptimized) {
				int[] idx = MyArrays.getTop(weights.clone(), threshold, false);
				MyArrays.set(weights, idx, 0.0f);
				System.out.print("	After Optimized: "
						+ MyArrays.countNoneZero(weights));
			}
			System.out.println();

			if (devset != null) {
				evaluate(devset);
			}
			System.out.println();

			if (method == TrainMethod.Average) {
				for (int i = 0; i < innerWeights.length; i++) {
					averageWeights[i] += innerWeights[i] / numSamples;
				}
			} else if (method == TrainMethod.FastAverage) {
				for (int i = 0; i < weights.length; i++) {
					averageWeights[i] += weights[i];
				}
			}

			if (interim) {
				Linear p = new Linear(inferencer, trainset.getAlphabetFactory());
				try {
					p.saveTo("tmp.model");
				} catch (IOException e) {
					System.err.println("write model error!");
				}
			}
			hisErrRate[iter%historyNum] = curErrRate;
			if(MyArrays.viarance(hisErrRate) < eps){
				System.out.println("convergence!");
				break;	
			}
		}

		if (method == TrainMethod.Average || method == TrainMethod.FastAverage) {
			for (int i = 0; i < averageWeights.length; i++) {
				averageWeights[i] /= iternum;
			}
			weights = null;
			weights = averageWeights;
			inferencer.setWeights(weights);
		}

		System.out.print("Non-Zero Weight Numbers: " + MyArrays.countNoneZero(weights));
		if (finalOptimized) {
			int[] idx = MyArrays.getTop(weights.clone(), threshold, false);
			MyArrays.set(weights, idx, 0.0f);
			System.out.print("	After Optimized: "
					+ MyArrays.countNoneZero(weights));
		}
		System.out.println();

		endTime = System.currentTimeMillis();
		System.out.println("time escape:" + (endTime - beginTime) / 1000.0
				+ "s");
		System.out.println();
		Linear p = new Linear(inferencer, trainset.getAlphabetFactory());
		return p;
	}

	@Override
	public void evaluate(InstanceSet devset) {
		float err = 0;
		float errtot = 0;
		int total = 0;
		for (int i = 0; i < devset.size(); i++) {
			Instance inst = devset.getInstance(i);
			total += inst.length();
			Predict pred = (Predict) inferencer.getBest(inst);
			float l = loss.calc(pred.getLabel(0), inst.getTarget());
			if (l > 0) {
				errtot += 1.0;
				err += l;
			}

		}
		if (!simpleOutput) {
			System.out.print("Test:");
			System.out.print(total - err);
			System.out.print('/');
			System.out.print(total);
			System.out.print("  Tag acc:");
		} else {
			System.out.print("  ");
		}
		System.out.print(1 - err / total);
		if (!simpleOutput) {
			System.out.print("  Sentence acc:");
			System.out.println(1 - errtot / devset.size());

		}
	}

}
