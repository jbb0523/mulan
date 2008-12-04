package mulan.classifier.lazy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import mulan.classifier.Prediction;
import mulan.core.Util;
import mulan.evaluation.BinaryPrediction;
import mulan.evaluation.IntegratedEvaluation;
import weka.classifiers.lazy.IBk;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.Utils;
import weka.core.neighboursearch.LinearNNSearch;

/**
 * Simple BR implementation of the KNN algorithm <!-- globalinfo-start -->
 * 
 * <pre>
 * Class implementing the base BRkNN algorithm and its 2 extensions BRkNN-a and BRkNN-b.
 * </pre>
 * 
 * For more information:
 * 
 * <pre>
 * E. Spyromitros, G. Tsoumakas, I. Vlahavas, An Empirical Study of Lazy Multilabel Classification Algorithms,
 * Proc. 5th Hellenic Conference on Artificial Intelligence (SETN 2008), Springer, Syros, Greece, 2008.
 * http://mlkd.csd.auth.gr/multilabel.html
 * </pre>
 * 
 * <!-- globalinfo-end -->
 * 
 * <!-- technical-bibtex-start --> BibTeX:
 * 
 * <p/>
 * <!-- technical-bibtex-end -->
 * 
 * @author Eleftherios Spyromitros-Xioufis ( espyromi@csd.auth.gr )
 * 
 */
@SuppressWarnings("serial")
public class BRkNN extends MultiLabelKNN {

	/**
	 * Stores the average number of labels among the knn for each instance Used
	 * in BRkNN-b extension
	 */
	int avgPredictedLabels;

	/**
	 * Random number generator used for tie breaking
	 */
	protected Random Rand;

	/**
	 * The value of kNN provided by the user. This may differ from
	 * numOfNeighbors if cross-validation is being used.
	 */
	private int cvMaxK;

	/**
	 * Whether to select k by cross validation.
	 */
	private boolean cvkSelection = false;

	/**
	 * Meaningful values are 0,2 and 3
	 */
	protected int selectedMethod;

	public static final int BR = 0;

	public static final int BRexta = 2;

	public static final int BRextb = 3;

	/**
	 * The default constructor. (The base algorithm)
	 * 
	 * @param numLabels
	 * @param numOfNeighbors
	 */
	public BRkNN(int numLabels, int numOfNeighbors) {
		super(numLabels, numOfNeighbors);
		distanceWeighting = WEIGHT_NONE; // weight none
		selectedMethod = BR; // the default method
		Rand = new Random(1);
	}

	/**
	 * Constructor giving the option to select an extension of the base version
	 * 
	 * @param numLabels
	 * @param numOfNeighbors
	 * @param method
	 *            (2 for BRkNN-a 3 for BRkNN-b)
	 * 
	 */
	public BRkNN(int numLabels, int numOfNeighbors, int method) {
		super(numLabels, numOfNeighbors);
		distanceWeighting = WEIGHT_NONE; // weight none
		selectedMethod = method;
		Rand = new Random(1);
	}

	public void buildClassifier(Instances aTrain) throws Exception {
		super.buildClassifier(aTrain);

		lnn = new LinearNNSearch();
		lnn.setDistanceFunction(dfunc);
		lnn.setInstances(train);
		lnn.setMeasurePerformance(false);

		if (cvkSelection == true) {
			crossValidate();
		}
	}

	/**
	 * 
	 * @param flag
	 *            if true the k is selected via cross-validation
	 */
	public void setkSelectionViaCV(boolean flag) {
		cvkSelection = flag;
	}

	/**
	 * Select the best value for k by hold-one-out cross-validation. Hamming
	 * Loss is minimized
	 * 
	 * @throws Exception
	 */
	protected void crossValidate() throws Exception {
		try {
			// the performance for each different k
			double[] performanceMetric = new double[cvMaxK];

			for (int i = 0; i < cvMaxK; i++) {
				performanceMetric[i] = 0;
			}

			Instance instance;
			Instances neighbours;
			double[] origDistances, convertedDistances;
			for (int i = 0; i < train.numInstances(); i++) {
				if (isDebug && (i % 50 == 0)) {
					debug("Cross validating " + i + "/" + train.numInstances()
							+ "\r");
				}
				instance = train.instance(i);
				neighbours = lnn.kNearestNeighbours(instance, cvMaxK);
				origDistances = lnn.getDistances();

				for (int j = cvMaxK; j > 0; j--) {
					// Update the performance metric
					convertedDistances = new double[origDistances.length];
					System.arraycopy(origDistances, 0, convertedDistances, 0,
							origDistances.length);
					double[] confidences = this.getConfidences(neighbours,
							convertedDistances);
					double[] predictions = null;

					if (selectedMethod == BR) {// BRknn
						predictions = labelsFromConfidences(confidences);
					} else if (selectedMethod == BRexta) {// BRknn-a
						predictions = labelsFromConfidences2(confidences);
					} else if (selectedMethod == BRextb) {// BRknn-b
						predictions = labelsFromConfidences3(confidences);
					}
					Prediction thisPrediction = new Prediction(predictions,
							confidences);

					BinaryPrediction[][] bpredictions = new BinaryPrediction[1][numLabels];
					for (int k = 0; k < numLabels; k++) {
						int classIdx = predictors + k;
						String classValue = instance.attribute(classIdx).value(
								(int) instance.value(classIdx));
						boolean actual = classValue.equals("1");
						bpredictions[0][k] = new BinaryPrediction(
								thisPrediction.getPrediction(k), actual,
								thisPrediction.getConfidence(k));
					}

					IntegratedEvaluation result = new IntegratedEvaluation(
							bpredictions);

					performanceMetric[j - 1] += result.hammingLoss();

					neighbours = new IBk().pruneToK(neighbours,
							convertedDistances, j - 1);
				}
			}

			// Display the results of the cross-validation
			for (int i = cvMaxK; i > 0; i--) {
				if (isDebug) {
					debug("Hold-one-out performance of " + (i) + " neighbors ");
				}
				if (isDebug) {
					debug("(Hamming Loss) = " + performanceMetric[i - 1]
							/ train.numInstances());
				}
			}

			// Check through the performance stats and select the best
			// k value (or the lowest k if more than one best)
			double[] searchStats = performanceMetric;

			double bestPerformance = Double.NaN;
			int bestK = 1;
			for (int i = 0; i < cvMaxK; i++) {
				if (Double.isNaN(bestPerformance)
						|| (bestPerformance > searchStats[i])) {
					bestPerformance = searchStats[i];
					bestK = i + 1;
				}
			}
			numOfNeighbors = bestK;
			if (isDebug) {
				System.err.println("Selected k = " + bestK);
			}

		} catch (Exception ex) {
			throw new Error("Couldn't optimize by cross-validation: "
					+ ex.getMessage());
		}
	}

	/**
	 * weka Ibk style prediction
	 */
	public Prediction makePrediction(Instance instance) throws Exception {

		// in cross-validation test-train instances does not belong to the same
		// data set
		// Instance instance2 = new Instance(instance);

		Instances knn = lnn.kNearestNeighbours(instance, numOfNeighbors);

		double[] distances = lnn.getDistances();
		double[] confidences = getConfidences(knn, distances);
		double[] predictions = null;

		if (selectedMethod == BR) {// BRknn
			predictions = labelsFromConfidences(confidences);
		} else if (selectedMethod == BRexta) {// BRknn-a
			predictions = labelsFromConfidences2(confidences);
		} else if (selectedMethod == BRextb) {// BRknn-b
			predictions = labelsFromConfidences3(confidences);
		}
		Prediction results = new Prediction(predictions, confidences);
		return results;

	}

	/**
	 * Calculates the confidences of the labels, based on the neighboring
	 * instances
	 * 
	 * @param neighbours
	 *            the list of nearest neighboring instances
	 * @param distances
	 *            the distances of the neighbors
	 * @return the confidences of the labels
	 */
	private double[] getConfidences(Instances neighbours, double[] distances) {
		double total = 0, weight;
		double neighborLabels = 0;
		double[] confidences = new double[numLabels];

		// Set up a correction to the estimator
		for (int i = 0; i < numLabels; i++) {
			confidences[i] = 1.0 / Math.max(1, train.numInstances());
		}
		total = (double) numLabels / Math.max(1, train.numInstances());

		for (int i = 0; i < neighbours.numInstances(); i++) {
			// Collect class counts
			Instance current = neighbours.instance(i);
			distances[i] = distances[i] * distances[i];
			distances[i] = Math.sqrt(distances[i] / this.predictors);
			switch (distanceWeighting) {
			case WEIGHT_INVERSE:
				weight = 1.0 / (distances[i] + 0.001); // to avoid division by
				// zero
				break;
			case WEIGHT_SIMILARITY:
				weight = 1.0 - distances[i];
				break;
			default: // WEIGHT_NONE:
				weight = 1.0;
				break;
			}
			weight *= current.weight();

			for (int j = 0; j < numLabels; j++) {
				double value = Double.parseDouble(current.attribute(
						predictors + j).value(
						(int) current.value(predictors + j)));
				if (Utils.eq(value, 1.0)) {
					confidences[j] += weight;
					neighborLabels += weight;
				}
			}
			total += weight;
		}

		avgPredictedLabels = (int) Math.round(neighborLabels / total);
		// Normalise distribution
		if (total > 0) {
			Utils.normalize(confidences, total);
		}
		return confidences;
	}

	/**
	 * old style prediction (not in use)
	 * 
	 * @param instance
	 * @return
	 * @throws Exception
	 */
	public Prediction makePrediction2(Instance instance) throws Exception {
		double[] confidences = new double[numLabels];
		double[] predictions = new double[numLabels];

		LinearNNSearch lnn = new LinearNNSearch();
		lnn.setDistanceFunction(dfunc);
		lnn.setInstances(train);
		lnn.setMeasurePerformance(false);

		double[] votes = new double[numLabels];

		Instances knn = new Instances(lnn.kNearestNeighbours(instance,
				numOfNeighbors));

		for (int i = 0; i < numLabels; i++) {
			int aces = 0; // num of aces in Knn for i
			for (int k = 0; k < numOfNeighbors; k++) {
				double value = Double.parseDouble(train.attribute(
						predictors + i).value(
						(int) knn.instance(k).value(predictors + i)));
				if (Utils.eq(value, 1.0)) {
					aces++;
				}
			}
			votes[i] = aces;
		}

		for (int i = 0; i < numLabels; i++) {
			confidences[i] = (double) votes[i] / numOfNeighbors;
		}

		predictions = labelsFromConfidences(confidences);

		Prediction results = new Prediction(predictions, confidences);
		return results;
	}

	/**
	 * Derive output labels from distributions.
	 */
	protected double[] labelsFromConfidences(double[] confidences) {
		if (thresholds == null) {
			thresholds = new double[numLabels];
			Arrays.fill(thresholds, threshold);
		}

		double[] result = new double[confidences.length];
		for (int i = 0; i < result.length; i++) {
			if (confidences[i] >= thresholds[i]) {
				result[i] = 1.0;
			}
		}
		return result;
	}

	/**
	 * used for BRknn-a
	 */
	protected double[] labelsFromConfidences2(double[] confidences) {
		double[] result = new double[confidences.length];
		boolean flag = false; // check the case that no label is true

		for (int i = 0; i < result.length; i++) {
			if (confidences[i] >= threshold) {
				result[i] = 1.0;
				flag = true;
			}
		}
		// assign the class with the greater confidence
		if (flag == false) {
			int index = Util.RandomIndexOfMax(confidences, Rand);
			result[index] = 1.0;
		}
		return result;
	}

	/**
	 * used for BRkNN-b (break ties arbitrarily)
	 */
	protected double[] labelsFromConfidences3(double[] confidences) {
		double[] result = new double[numLabels];

		int[] indices = Utils.stableSort(confidences);

		ArrayList<Integer> lastindices = new ArrayList<Integer>();

		int counter = 0;
		int i = numLabels - 1;

		while (i > 0) {
			if (confidences[indices[i]] > confidences[indices[numLabels
					- avgPredictedLabels]]) {
				result[indices[i]] = 1.0;
				counter++;
			} else if (confidences[indices[i]] == confidences[indices[numLabels
					- avgPredictedLabels]]) {
				lastindices.add(indices[i]);
			} else {
				break;
			}
			i--;
		}

		int size = lastindices.size();

		int j = avgPredictedLabels - counter;
		while (j > 0) {
			int next = Rand.nextInt(size);
			if (result[lastindices.get(next)] != 1.0) {
				result[lastindices.get(next)] = 1.0;
				j--;
			}
		}

		return result;
	}

	/**
	 * old style used for BRkNN-b (not in use)
	 */
	protected double[] labelsFromConfidences3old(double[] confidences) {
		double[] result = new double[numLabels];

		double[] conf2 = new double[numLabels];
		for (int i = 0; i < numLabels; i++) {
			conf2[i] = confidences[i];
		}

		for (int i = 0; i < avgPredictedLabels; i++) {
			int maxindex = Utils.maxIndex(conf2);
			result[maxindex] = 1.0;
			conf2[maxindex] = -1.0;
		}
		return result;
	}

	/**
	 * set the maximum number of neighbors to be evaluated
	 * via cross-validation
	 * 
	 * @param cvMaxK
	 */
	public void setCvMaxK(int cvMaxK) {
		this.cvMaxK = cvMaxK;
	}

}