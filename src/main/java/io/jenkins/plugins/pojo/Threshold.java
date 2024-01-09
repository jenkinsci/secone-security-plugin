package io.jenkins.plugins.pojo;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class Threshold {

	private String criticalThreshold;
	private String highThreshold;
	private String mediumThreshold;
	private String lowThreshold;
	private String statusAction;
	
	@DataBoundConstructor
	public Threshold(String criticalThreshold, String highThreshold, String mediumThreshold, String lowThreshold, String statusAction) {
		this.criticalThreshold = criticalThreshold;
		this.highThreshold = highThreshold;
		this.mediumThreshold = mediumThreshold;
		this.lowThreshold  = lowThreshold;
		this.statusAction = statusAction;
	}
	
	public String getCriticalThreshold() {
		return criticalThreshold;
	}

	@DataBoundSetter
	public void setCriticalThreshold(String criticalThreshold) {
		this.criticalThreshold = criticalThreshold;
	}

	public String getHighThreshold() {
		return highThreshold;
	}

	@DataBoundSetter
	public void setHighThreshold(String highThreshold) {
		this.highThreshold = highThreshold;
	}

	public String getMediumThreshold() {
		return mediumThreshold;
	}

	@DataBoundSetter
	public void setMediumThreshold(String mediumThreshold) {
		this.mediumThreshold = mediumThreshold;
	}

	public String getLowThreshold() {
		return lowThreshold;
	}

	@DataBoundSetter
	public void setLowThreshold(String lowThreshold) {
		this.lowThreshold = lowThreshold;
	}

	public String getStatusAction() {
		return statusAction;
	}

	@DataBoundSetter
	public void setStatusAction(String statusAction) {
		this.statusAction = statusAction;
	}
}
