// BSD License (http://lemurproject.org/galago-license)
package org.lemurproject.galago.core.retrieval.structured;

import org.lemurproject.galago.core.retrieval.FeatureFactory;
import java.util.ArrayList;
import junit.framework.TestCase;
import org.lemurproject.galago.core.retrieval.query.Node;
import org.lemurproject.galago.core.retrieval.query.NodeType;
import org.lemurproject.galago.core.retrieval.iterator.BaseIterator;
import org.lemurproject.galago.core.retrieval.iterator.OrderedWindowIterator;
import org.lemurproject.galago.core.retrieval.iterator.NullExtentIterator;
import org.lemurproject.galago.core.retrieval.iterator.ScoreCombinationIterator;
import org.lemurproject.galago.core.retrieval.iterator.SynonymIterator;
import org.lemurproject.galago.core.retrieval.query.NodeParameters;
import org.lemurproject.galago.tupleflow.Parameters;

/**
 *
 * @author trevor, irmarc
 */
public class FeatureFactoryTest extends TestCase {

  public FeatureFactoryTest(String testName) {
    super(testName);
  }

  /**
   * Test of getClassName method, of class FeatureFactory.
   */
  public void testGetClassName() throws Exception {
    FeatureFactory f = new FeatureFactory(new Parameters());
    String actual = f.getClassName(new Node("syn", "fakeargument"));
    assertEquals(SynonymIterator.class.getName(), actual);
  }

  /**
   * Test of getClass method, of class FeatureFactory.
   */
  public void testGetClass() throws Exception {
    FeatureFactory f = new FeatureFactory(new Parameters());
    Class c = f.getClass(new Node("combine", ""));
    assertEquals(ScoreCombinationIterator.class.getName(), c.getName());
  }

  /**
   * Test of getNodeType method, of class FeatureFactory.
   */
  public void testGetNodeType() throws Exception {
    FeatureFactory f = new FeatureFactory(new Parameters());
    NodeType type = f.getNodeType(new Node("combine", ""));
    Class c = type.getIteratorClass();
    assertEquals(ScoreCombinationIterator.class.getName(), c.getName());
  }

  /**
   * Test of getIterator method, of class FeatureFactory.
   */
  public void testGetIterator() throws Exception {
    FeatureFactory f = new FeatureFactory(new Parameters());
    ArrayList<BaseIterator> iterators = new ArrayList();
    iterators.add(new NullExtentIterator());

    NodeParameters np = new NodeParameters();
    np.set("default", 5);
    BaseIterator iterator = f.getIterator(new Node("od", np, new ArrayList(), 0), iterators);
    assertEquals(OrderedWindowIterator.class.getName(), iterator.getClass().getName());
  }

  public void testGetClassNameConfig() throws Exception {
    String config = "{ \"operators\" : {\"a\" : \"b\" } }";
    Parameters p = Parameters.parseString(config);
    FeatureFactory f = new FeatureFactory(p);

    assertEquals("b", f.getClassName(new Node("a", new ArrayList())));
  }
}
