/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.context;

import java.util.*;
import java.util.Hashtable;

public class ContextStack implements Map {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ContextStack.class);

    protected Deque<Map> stackList = new LinkedList<Map>();
    protected Map firstMap = null;

    public ContextStack() {
        // start with a single Map
        push();
    }

    /** Puts a new Map on the top of the stack for a fresh local context
     * @return Returns reference to this ContextStack
     */
    public ContextStack push() {
        Map newMap = new HashMap();
        stackList.addFirst(newMap);
        firstMap = newMap;
        return this;
    }

    /** Puts an existing Map on the top of the stack (top meaning will override lower layers on the stack)
     * @param existingMap An existing Map
     */
    public void push(Map existingMap) {
        if (existingMap == null) throw new IllegalArgumentException("Cannot push null as an existing Map");
        stackList.addFirst(existingMap);
        firstMap = existingMap;
    }

    /** Remove and returns the Map from the top of the stack (the local context).
     * If there is only one Map on the stack it returns null and does not remove it.
     *
     * @return The first/top Map
     */
    public Map pop() {
        Map popped = stackList.size() > 0 ? stackList.removeFirst() : null;
        firstMap = stackList.size() > 0 ? stackList.peekFirst() : null;
        return popped;
    }

    /** Add an existing Map as the Root Map, ie on the BOTTOM of the stack meaning it will be overridden by other Maps on the stack
     * @param  existingMap An existing Map
     */
    public void addRootMap(Map existingMap) {
        if (existingMap == null) throw new IllegalArgumentException("Cannot add null as an existing Map");
        stackList.addLast(existingMap);
    }

    public Map getRootMap() { return stackList.peekLast(); }

    /**
     * Creates a ContextStack object that has the same Map objects on its stack (a shallow clone).
     * Meant to be used to enable a situation where a parent and child context are operating simultaneously using two
     * different ContextStack objects, but sharing the Maps between them.
     *
     * @return Clone of this ContextStack
     */
    public ContextStack clone() throws CloneNotSupportedException {
        ContextStack newStack = new ContextStack();
        newStack.stackList.addAll(stackList);
        return newStack;
    }

    /** @see java.util.Map#size() */
    public int size() {
        // use the keySet since this gets a set of all unique keys for all Maps in the stack
        Set keys = keySet();
        return keys.size();
    }

    /** @see java.util.Map#isEmpty() */
    public boolean isEmpty() {
        for (Map curMap: stackList) {
            if (!curMap.isEmpty()) return false;
        }
        return true;
    }

    /** @see java.util.Map#containsKey(java.lang.Object) */
    public boolean containsKey(Object key) {
        /* NOTE: this would be the proper way to do it, but to avoid problems with fields not defined in Groovy, etc
         *     we'll always pretend like it is defined and null. May consider not doing this at some point and find
         *     a better solution.
        for (Map curMap: stackList) {
            if (key == null && curMap instanceof Hashtable) continue;
            if (curMap.containsKey(key)) return true;
        }
        return false;
        */
        return true;
    }

    /** @see java.util.Map#containsValue(java.lang.Object) */
    public boolean containsValue(Object value) {
        // this keeps track of keys looked at for values at each level of the stack so that the same key is not
        // considered more than once (the earlier Maps overriding later ones)
        Set<Object> keysObserved = new HashSet<Object>();
        for (Map curMap: stackList) {
            for (Map.Entry curEntry: (Set<Map.Entry>) curMap.entrySet()) {
                if (!keysObserved.contains(curEntry.getKey())) {
                    keysObserved.add(curEntry.getKey());
                    if (value == null) {
                        if (curEntry.getValue() == null) return true;
                    } else {
                        if (value.equals(curEntry.getValue())) return true;
                    }
                }
            }
        }
        return false;
    }

    /** @see java.util.Map#get(java.lang.Object) */
    public Object get(Object key) {
        // the "context" key always gets a self-reference, effectively the top of the stack
        if ("context".equals(key)) return this;
        if (firstMap.containsKey(key)) {
            return firstMap.get(key);
        } else {
            Object value = null;
            for (Map curMap: stackList) {
                try {
                    if (key == null && curMap instanceof Hashtable) continue;
                    if (curMap.containsKey(key)) {
                        value = curMap.get(key);
                        break;
                    }
                } catch (Exception e) {
                    logger.error("Error getting value for key [" + key + "], returning null", e);
                    return null;
                }
            }
            return value;
        }
    }

    /** @see java.util.Map#  */
    public Object put(Object key, Object value) {
        return firstMap.put(key, value);
    }

    /** @see java.util.Map#remove(java.lang.Object) */
    public Object remove(Object key) {
        return firstMap.remove(key);
    }

    /** @see java.util.Map#putAll(java.util.Map) */
    public void putAll(Map arg0) { firstMap.putAll(arg0); }

    /** @see java.util.Map#clear() */
    public void clear() { firstMap.clear(); }

    /** @see java.util.Map#keySet() */
    public Set keySet() {
        Set<Object> resultSet = new HashSet<Object>();
        for (Map curMap: stackList) {
            resultSet.addAll(curMap.keySet());
        }
        return Collections.unmodifiableSet(resultSet);
    }

    /** @see java.util.Map#values() */
    public Collection values() {
        Set<Object> keysObserved = new HashSet<Object>();
        List<Object> resultValues = new LinkedList<Object>();
        for (Map curMap: stackList) {
            for (Map.Entry curEntry: (Set<Map.Entry>) curMap.entrySet()) {
                if (!keysObserved.contains(curEntry.getKey())) {
                    keysObserved.add(curEntry.getKey());
                    resultValues.add(curEntry.getValue());
                }
            }
        }
        return Collections.unmodifiableCollection(resultValues);
    }

    /** @see java.util.Map#entrySet() */
    public Set<Map.Entry> entrySet() {
        Set<Object> keysObserved = new HashSet<Object>();
        Set<Map.Entry> resultEntrySet = new HashSet<Map.Entry>();
        for (Map curMap: stackList) {
            for (Map.Entry curEntry: (Set<Map.Entry>) curMap.entrySet()) {
                if (!keysObserved.contains(curEntry.getKey())) {
                    keysObserved.add(curEntry.getKey());
                    resultEntrySet.add(curEntry);
                }
            }
        }
        return Collections.unmodifiableSet(resultEntrySet);
    }

    @Override
    public String toString() {
        StringBuilder fullMapString = new StringBuilder();
        int curLevel = 0;
        for (Map curMap: stackList) {
            fullMapString.append("============================== Start stack level ").append(curLevel).append("\n");
            for (Map.Entry curEntry: (Set<Map.Entry>) curMap.entrySet()) {
                fullMapString.append("==>[");
                fullMapString.append(curEntry.getKey());
                fullMapString.append("]:");
                if (curEntry.getValue() instanceof ContextStack) {
                    // skip instances of ContextStack to avoid infinite recursion
                    fullMapString.append("<Instance of ContextStack, not printing to avoid infinite recursion>");
                } else {
                    fullMapString.append(curEntry.getValue());
                }
                fullMapString.append("\n");
            }
            fullMapString.append("============================== End stack level ").append(curLevel).append("\n");
            curLevel++;
        }
        return fullMapString.toString();
    }

    @Override
    public int hashCode() { return this.stackList.hashCode(); }

    @Override
    public boolean equals(Object o) {
        return !(o == null || o.getClass() != this.getClass()) && this.stackList.equals(((ContextStack) o).stackList);
    }
}
