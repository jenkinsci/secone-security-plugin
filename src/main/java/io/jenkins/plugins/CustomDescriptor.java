package io.jenkins.plugins;

import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;

public class CustomDescriptor extends BuildStepDescriptor<Builder> {

	@Override
	public boolean isApplicable(Class<? extends AbstractProject> jobType) {
		return false;
	}
	
	public boolean requiresWorkspace() {
		return false;
	}
}
