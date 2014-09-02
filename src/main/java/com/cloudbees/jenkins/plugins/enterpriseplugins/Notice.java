/*
 * The MIT License
 *
 * Copyright (c) 2011-2013, CloudBees, Inc., Stephen Connolly.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cloudbees.jenkins.plugins.enterpriseplugins;

import hudson.Extension;
import hudson.model.ManagementLink;
import jenkins.model.Jenkins;
import org.jvnet.localizer.Localizable;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Displays the enterprise plugins progress notices
 */
@Extension
public class Notice extends ManagementLink {

    public Localizable getStatus() {
        return PluginImpl.getStatus();
    }

    @Override public String getIconFileName() {
        // TODO maybe get a CloudBees icon?
        return PluginImpl.isEverythingInstalled() ? null : "installer.png";
    }

    @Override public String getUrlName() {
        return "installJEbC";
    }

    @Override public String getDisplayName() {
        return Messages.Notice_displayName();
    }

    @Override public String getDescription() {
        return Messages.Notice_description();
    }

    @RequirePOST
    public HttpResponse doInstall(@QueryParameter boolean full) throws Exception {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
        PluginImpl.installPlugins(full);
        return HttpResponses.redirectToDot();
    }

}
