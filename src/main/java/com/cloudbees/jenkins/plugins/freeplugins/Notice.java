package com.cloudbees.jenkins.plugins.freeplugins;

import hudson.Extension;
import hudson.model.Hudson;
import hudson.model.PageDecorator;
import hudson.model.RootAction;
import org.jvnet.localizer.Localizable;

/**
 * Displays the freeplugins progress notices
 */
@Extension
public class Notice extends PageDecorator {

    public Notice() {
        super(Notice.class);
    }

    public boolean isNagDue() {
        return getStatus() != null;
    }

    public Localizable getStatus() {
        return PluginImpl.getStatus();
    }

    public static Notice getInstanceOrDie() {
        for (PageDecorator decorator: PageDecorator.all()) {
            if (decorator instanceof Notice) {
                return (Notice)decorator;
            }
        }
        throw new AssertionError(Notice.class + " is missing from the extension list");
    }

    @Extension
    public static class RootActionImpl implements RootAction {

        public String getIconFileName() {
            return null; // hidden
        }

        public String getDisplayName() {
            return null; // hidden
        }

        public String getUrlName() {
            return Notice.class.getName();
        }

        public Notice getInstance() {
            return getInstanceOrDie();
        }
    }

}
