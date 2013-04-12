/**
 *
 */
package org.jvnet.jenkins.plugins.nodelabelparameter;

import hudson.Extension;
import hudson.model.*;

import java.util.*;

import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Defines a build parameter used to select the node where a job should be
 * executed on. Although it is possible to define the node name in the UI at
 * "restrict where this job should run", but that would tide a job to a fix
 * node. This parameter actually allows to define a list of possible nodes and
 * ask the user before execution.
 *
 * @author domi
 *
 */
public class NodeParameterDefinition extends SimpleParameterDefinition {

	private static final long serialVersionUID = 1L;

	public static final String ALL_NODES = "ALL (no restriction)";

	public final List<String> allowedSlaves;
	private List<String> defaultSlaves;
	@Deprecated
	public transient String defaultValue;
	private String triggerIfResult;
	private boolean allowMultiNodeSelection;
	private boolean triggerConcurrentBuilds;
	private boolean ignoreOfflineNodes;

    @DataBoundConstructor
    public NodeParameterDefinition(String name, String description, List<String> defaultSlaves, List<String> allowedSlaves, String triggerIfResult, boolean ignoreOfflineNodes) {
        super(name, description);
        this.allowedSlaves = allowedSlaves;
        this.defaultSlaves = defaultSlaves;

        if ("multiSelectionDisallowed".equals(triggerIfResult)) {
            this.allowMultiNodeSelection = false;
            this.triggerConcurrentBuilds = false;
        } else if ("allowMultiSelectionForConcurrentBuilds".equals(triggerIfResult)) {
            this.allowMultiNodeSelection = true;
            this.triggerConcurrentBuilds = true;
        } else {
            this.allowMultiNodeSelection = true;
            this.triggerConcurrentBuilds = false;
        }
        this.triggerIfResult = triggerIfResult;
        this.ignoreOfflineNodes = ignoreOfflineNodes;
    }
    
    @Deprecated
	public NodeParameterDefinition(String name, String description, String defaultValue, List<String> allowedSlaves, String triggerIfResult) {
		super(name, description);
		this.allowedSlaves = allowedSlaves;

		if (this.allowedSlaves != null && this.allowedSlaves.contains(defaultValue)) {
			this.allowedSlaves.remove(defaultValue);
			this.allowedSlaves.add(0, defaultValue);
		}

		if ("multiSelectionDisallowed".equals(triggerIfResult)) {
			this.allowMultiNodeSelection = false;
			this.triggerConcurrentBuilds = false;
		} else if ("allowMultiSelectionForConcurrentBuilds".equals(triggerIfResult)) {
			this.allowMultiNodeSelection = true;
			this.triggerConcurrentBuilds = true;
		} else {
			this.allowMultiNodeSelection = true;
			this.triggerConcurrentBuilds = false;
		}
		this.triggerIfResult = triggerIfResult;
		this.ignoreOfflineNodes = false;
	}
	
	public List<String> getDefaultSlaves() {
        return defaultSlaves;
    }
	
	public boolean isIgnoreOfflineNodes() {
        return ignoreOfflineNodes;
    }

	/**
	 * e.g. what to show if a build is triggered by hand?
	 */
	@Override
	public NodeParameterValue getDefaultParameterValue() {
		return new NodeParameterValue(getName(), getDefaultSlaves(), isIgnoreOfflineNodes());
	}

	@Override
	public ParameterValue createValue(String value) {
		return new NodeParameterValue(getName(), getDescription(), value);
	}

	@Override
	public ParameterDefinition copyWithDefaultValue(ParameterValue defaultValueObj) {
		return this;
	}

	/**
	 * Returns a list of nodes the job could run on. If allowed nodes is empty,
	 * it falls back to all nodes
	 *
	 * @return list of nodenames.
	 */
	public List<String> getAllowedNodesOrAll() {
		final List<String> slaves = allowedSlaves == null || allowedSlaves.isEmpty() || allowedSlaves.contains(ALL_NODES) ? getSlaveNames() : allowedSlaves;

		Collections.sort(slaves, NodeNameComparator.INSTANCE);

		return slaves;
	}

	/**
	 * @return the triggerIfResult
	 */
	public String getTriggerIfResult() {
		return triggerIfResult;
	}

	/**
	 * returns all available nodes plus an identifier to identify all slaves at
	 * position one.
	 *
	 * @return list of node names
	 */
	public static List<String> getSlaveNamesForSelection() {
		List<String> slaveNames = getSlaveNames();
		Collections.sort(slaveNames, NodeNameComparator.INSTANCE);
		slaveNames.add(0, ALL_NODES);
		return slaveNames;
	}

	/**
	 * Gets the names of all configured slaves, regardless whether they are
	 * online.
	 *
	 * @return list with all slave names
	 */
	@SuppressWarnings("deprecation")
	public static List<String> getSlaveNames() {
		ComputerSet computers = Hudson.getInstance().getComputer();
		List<String> slaveNames = computers.get_slaveNames();

		// slaveNames is unmodifiable, therefore create a new list
		List<String> test = new ArrayList<String>();
		test.addAll(slaveNames);

		// add 'magic' name for master, so all nodes can be handled the same way
		if (!test.contains("master")) {
			test.add(0, "master");
		}

        //Adding our custom nodes - which means we are adding all the names of the jobs with a special mark
        for( String jobName : Jenkins.getInstance().getJobNames()) {
            test.add(String.format("%s - Node", jobName));
        }

		return test;
	}
	
	/**
	 * Comparator preferring the master name
	 */
	private static final class NodeNameComparator implements Comparator<String> {
	    public static final NodeNameComparator INSTANCE = new NodeNameComparator();
        public int compare(String o1, String o2) {
            if("master".endsWith(o1)){
                return -1;
            }
            return o1.compareTo(o2);
        }
    }

    @Extension
	public static class DescriptorImpl extends ParameterDescriptor {
		@Override
		public String getDisplayName() {
			return "Node";
		}

		@Override
		public String getHelpFile() {
			return "/plugin/nodelabelparameter/nodeparam.html";
		}
	}

	@Override
	public ParameterValue createValue(StaplerRequest req, JSONObject jo) {
		// as String from UI: {"labels":"master","name":"HOSTN"}
		// as JSONArray: {"name":"HOSTN","value":["master","host2"]}
		// as String from script: {"name":"HOSTN","value":"master"}
		final String name = jo.getString("name");
		final Object joValue = jo.get("value") == null ? jo.get("labels") : jo.get("value");

		List<String> nodes = new ArrayList<String>();
		if (joValue instanceof String) {
            String labelValue = (String) joValue;
            if(shouldLookForParentNode(labelValue)) {
                labelValue = getJobLastRunNode(labelValue);
            }
			nodes.add(labelValue);

		} else if (joValue instanceof JSONArray) {
			JSONArray ja = (JSONArray) joValue;
			for (Object strObj : ja) {
				nodes.add((String) strObj);
			}
		}

		NodeParameterValue value = new NodeParameterValue(name, nodes, isIgnoreOfflineNodes());
		value.setDescription(getDescription());
		return value;
	}

    /**
     * TODO by baris
     * @param labelValue
     * @return
     */
    private String getJobLastRunNode(String labelValue) {
        String jobName = labelValue.split("-")[0].trim();
        Collection<? extends Job> jobs = Jenkins.getInstance().getItem(jobName).getAllJobs();
        for(Job job : jobs) {
            String nodeName = ((FreeStyleProject) job).getLastStableBuild().getBuiltOn().getNodeName() ;
            return nodeName.equals("")  ? "master" : nodeName;
        }
        return labelValue;
    }

    /**
     * <p>This method will check if the label that has come from the UI is related to another job.
     *    If it is, we have to get the real Job Name
     * </p>
     *
     * @param labelValue The label value that comes from the UI
     * @return true if labelValue ends with ' - Node', else false
     */
    public boolean shouldLookForParentNode(String labelValue) {
        return labelValue.indexOf(" - Node") > -1;
    }

	/**
	 * @return the allowMultiNodeSelection
	 */
	public boolean getAllowMultiNodeSelection() {
		return allowMultiNodeSelection;
	}

	/**
	 * @return the triggerConcurrentBuilds
	 */
	public boolean isTriggerConcurrentBuilds() {
		return triggerConcurrentBuilds;
	}
	
    /*
     * keep backward compatible
     */
    public Object readResolve() {
        if (defaultValue != null) {
            if (defaultSlaves == null) {
                defaultSlaves = new ArrayList<String>();
            }
            defaultSlaves.add(defaultValue);
        }
        return this;
    }	

}
