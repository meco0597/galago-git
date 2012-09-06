// BSD License (http://lemurproject.org/galago-license)
package org.lemurproject.galago.core.retrieval;

import gnu.trove.list.array.TIntArrayList;
import gnu.trove.set.hash.TIntHashSet;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import org.lemurproject.galago.core.index.AggregateReader.AggregateIterator;
import org.lemurproject.galago.core.index.AggregateReader.CollectionStatistics;
import org.lemurproject.galago.core.index.AggregateReader.NodeStatistics;
import org.lemurproject.galago.core.index.Index;
import org.lemurproject.galago.core.retrieval.iterator.*;
import org.lemurproject.galago.core.retrieval.query.Node;
import org.lemurproject.galago.core.retrieval.processing.*;
import org.lemurproject.galago.core.scoring.Estimator;
import org.lemurproject.galago.tupleflow.Parameters;
import org.lemurproject.galago.tupleflow.Utility;

/**
 * An extension to the LocalRetrieval to support certain statistics used
 * in staged evaluation.
 * @author irmarc
 */
public class StagedLocalRetrieval extends LocalRetrieval {

  /**
   * One retrieval interacts with one index. Parameters dictate the behavior
   * during retrieval time, and selection of the appropriate feature factory.
   * Additionally, the supplied parameters will be passed forward to the chosen
   * feature factory.
   */
  public StagedLocalRetrieval(Index index) throws IOException {
    super(index, new Parameters());
  }

  public StagedLocalRetrieval(Index index, Parameters parameters) throws IOException {
    super(index, parameters);
    checkForSyntheticCounts();
  }

  /**
   * For this constructor, being sent a filename path to the indicies, we first
   * list out all the directories in the path. If there are none, then we can
   * safely assume that the filename specifies a single index (the files listed
   * are all parts), otherwise we will treat each subdirectory as a separate
   * logical index.
   */
  public StagedLocalRetrieval(String filename, Parameters parameters)
          throws FileNotFoundException, IOException, Exception {
    super(filename, parameters);
    checkForSyntheticCounts();
  }

  protected StructuredIterator createNodeMergedIterator(Node node, ScoringContext context,
          HashMap<String, StructuredIterator> iteratorCache)
          throws Exception {
    StructuredIterator iterator = super.createNodeMergedIterator(node, context, iteratorCache);
    // Everything in the if will pre-load the collection statistics for the estimators.
    // This will cause the estimators to have accurate collection stats for the first pass.
    // So, obviously, correction is not needed.
    if (ContextualIterator.class.isInstance(iterator)
            && (context != null) && globalParameters.containsKey("syntheticCounts")
            && SoftDeltaScoringContext.class.isAssignableFrom(context.getClass())
            && Estimator.class.isAssignableFrom(iterator.getClass())) {
      String key = node.getChild(0).getNodeParameters().getString("key");
      NodeStatistics ns = syntheticCounts.get(key);
      SoftDeltaScoringContext sdsc = (SoftDeltaScoringContext) context;
      if (globalParameters.getString("scorer").equals("bm25")) {
        int count = (int) ((ns.nodeDocumentCount == 0) ? 1 : ns.nodeDocumentCount);
        sdsc.lo_accumulators.put(((Estimator) iterator), count);
        sdsc.hi_accumulators.put(((Estimator) iterator), count);
      } else {
        int count = (int) (ns.nodeFrequency == 0 ? 1 : ns.nodeFrequency);
        sdsc.lo_accumulators.put(((Estimator) iterator), count);
        sdsc.hi_accumulators.put(((Estimator) iterator), count);
      }
    }
    return iterator;
  }

  @Override
  public NodeStatistics nodeStatistics(Node root) throws Exception {
    NodeStatistics stats = new NodeStatistics();
    // set up initial values
    stats.node = root.toString();
    stats.nodeDocumentCount = 0;
    stats.maximumCount = 0;
    stats.nodeFrequency = 0;
    stats.collectionLength = getRetrievalStatistics().collectionLength;
    stats.documentCount = getRetrievalStatistics().documentCount;

    StructuredIterator structIterator = createIterator(new Parameters(), root, null);
    if (AggregateIterator.class.isInstance(structIterator)) {
      stats = ((AggregateIterator) structIterator).getStatistics();

    } else if (syntheticCounts != null && windowOps.contains(root.getOperator())) {
      String key = AbstractPartialProcessor.makeNodeKey(root);
      stats = syntheticCounts.get(key); // use the cached numbers
    } else if (structIterator instanceof MovableCountIterator) {
      // Make sure we're not sharing
      boolean actuallySharing = globalParameters.get("shareNodes", false);
      globalParameters.set("shareNodes", false);

      MovableCountIterator iterator = (MovableCountIterator) structIterator;
      if (this.globalParameters.containsKey("completion")) {
        String mode = this.globalParameters.getString("completion");
        if (mode.equals("onepass")) {
          onepassStats(stats, iterator);
        } else if (mode.equals("sampled")) {
          sampleOnlyStats(stats, iterator);
        } else {
          defaultStats(stats, iterator);
        }
      } else {
        defaultStats(stats, iterator);
      }
      globalParameters.set("shareNodes", actuallySharing);
    } else {
      throw new IllegalArgumentException("Node " + root.toString() + " did not return a counting iterator.");
    }

    return stats;
  }

  // Nothing special
  private void defaultStats(NodeStatistics stats, MovableCountIterator it) throws Exception {
    while (!it.isDone()) {
      int candidate = it.currentCandidate();
      if (it.hasMatch(candidate)) {
        stats.maximumCount = Math.max(stats.maximumCount, it.count());
        stats.nodeFrequency += it.count();
        stats.nodeDocumentCount++;
      }
      it.movePast(candidate);
    }
  }

  // Do the whole thing, but cache the counts of the candidates for use later
  private void onepassStats(NodeStatistics stats, MovableCountIterator it) throws Exception {
    assert (candidates != null);
    if (occurrenceCache == null) {
      occurrenceCache = new HashMap<String, PriorityQueue<Pair>>();
    }
    PriorityQueue<Pair> cache = new PriorityQueue<Pair>();
    occurrenceCache.put(Utility.toString(it.key()), cache);

    while (!it.isDone()) {
      int candidate = it.currentCandidate();
      if (it.hasMatch(candidate)) {
        stats.maximumCount = Math.max(stats.maximumCount, it.count());
        stats.nodeFrequency += it.count();
        stats.nodeDocumentCount++;
        if (candidates.contains(candidate)) {
          cache.add(new Pair(candidate, it.count()));
        }
        it.movePast(candidate);
      }
    }
  }

  // Only takes numbers from the supplied candidate list. :-o
  private void sampleOnlyStats(NodeStatistics stats, MovableCountIterator it) throws Exception {
    assert (sortedCandidates != null);
    if (occurrenceCache == null) {
      occurrenceCache = new HashMap<String, PriorityQueue<Pair>>();
    }
    PriorityQueue<Pair> cache = new PriorityQueue<Pair>();
    occurrenceCache.put(Utility.toString(it.key()), cache);

    for (int i = 0; i < sortedCandidates.size(); i++) {
      it.moveTo(sortedCandidates.get(i));
      if (it.isDone()) {
        break;
      }

      if (it.hasMatch(sortedCandidates.get(i))) {
        stats.maximumCount = Math.max(stats.maximumCount, it.count());
        stats.nodeFrequency += it.count();
        stats.nodeDocumentCount++;
        cache.add(new Pair(sortedCandidates.get(i), it.count()));
      }
    }
  }

  private void checkForSyntheticCounts() throws IOException {
    if (globalParameters.containsKey("syntheticCounts")) {
      windowOps = new HashSet<String>();
      windowOps.add("od");
      windowOps.add("uw");
      windowOps.add("ordered");
      windowOps.add("unordered");

      // read in counts
      syntheticCounts = new HashMap<String, NodeStatistics>();
      CollectionStatistics cs = this.getRetrievalStatistics();
      BufferedReader br = new BufferedReader(new FileReader(globalParameters.getString("syntheticCounts")));
      while (br.ready()) {
        String[] parts = br.readLine().split("\t");
        NodeStatistics ns = new NodeStatistics();
        String key = parts[0];
        ns.nodeFrequency = Long.parseLong(parts[1]);
        ns.maximumCount = Long.parseLong(parts[2]);
        ns.nodeDocumentCount = Long.parseLong(parts[3]);
        ns.collectionLength = cs.collectionLength;
        ns.documentCount = cs.documentCount;
        syntheticCounts.put(key, ns);
      }
      br.close();
      System.err.printf("LOADED %s synthetic count entries.\n", syntheticCounts.size());
    } else {
      windowOps = null;
      syntheticCounts = null;
    }
  }
  // HAX
  public TIntArrayList sortedCandidates;
  public TIntHashSet candidates;
  public HashMap<String, PriorityQueue<Pair>> occurrenceCache;
  public HashSet<String> windowOps;
  public HashMap<String, NodeStatistics> syntheticCounts;
}