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

import hudson.BulkChange;
import hudson.Plugin;
import hudson.PluginWrapper;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.lifecycle.RestartNotSupportedException;
import hudson.model.UpdateCenter;
import hudson.model.UpdateSite;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import hudson.util.FormValidation;
import hudson.util.PersistedList;
import hudson.util.TimeUnit2;
import hudson.util.VersionNumber;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContextHolder;
import org.jvnet.localizer.Localizable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.Timer;
import org.acegisecurity.context.SecurityContext;

/**
 * Installs the custom update site.
 */
public class PluginImpl extends Plugin {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PluginImpl.class.getName());

    /**
     * The current update center URL.
     */
    private static final String CLOUDBEES_UPDATE_CENTER_URL =
            "http://jenkins-updates.cloudbees.com/update-center.json";

    /**
     * The current update center URL and any previous URLs that were used for the same content and should be migrated
     * to the current one.
     */
    private static final Set<String> cloudBeesUpdateCenterUrls = new HashSet<String>(Arrays.asList(
            CLOUDBEES_UPDATE_CENTER_URL,
            "http://nectar-updates.cloudbees.com/update-center.json"
    ));

    /**
     * The current update center ID.
     */
    private static final String CLOUDBEES_UPDATE_CENTER_ID = "jenkins-enterprise";

    /**
     * The current update center ID and any previous IDs that were used for the same content and should be migrated
     * to the current one.
     */
    private static final Set<String> cloudBeesUpdateCenterIds = new HashSet<String>(Arrays.asList(
            CLOUDBEES_UPDATE_CENTER_ID,
            "ichci"
    ));

    private static final Dependency ASYNC_HTTP_CLIENT = require("async-http-client","1.7.8");
    private static final Dependency CLOUDBEES_LICENSE = require("cloudbees-license", "7.1");
    private static final Dependency NECTAR_LICENSE = require("nectar-license","7.1");
    private static final String CJOC_VERSION = "1.7.0";
    private static final Dependency OC_AGENT = require("operations-center-agent", CJOC_VERSION);
    private static final Dependency OC_ANALYTICS = require("operations-center-analytics-reporter", CJOC_VERSION);
    private static final Dependency OC_CONTEXT = require("operations-center-context", CJOC_VERSION);
    private static final Dependency OC_CLIENT = require("operations-center-client", CJOC_VERSION);
    private static final Dependency OC_CLOUD = require("operations-center-cloud", CJOC_VERSION);
    private static final Dependency OC_OPENID_CSE = require("operations-center-openid-cse", CJOC_VERSION);
    private static final String WORKFLOW_VERSION = "1.8";
    private static final String CLOUDBEES_WORKFLOW_VERSION = "1.3";

    public enum InstallMode {
        MINIMAL(ASYNC_HTTP_CLIENT, CLOUDBEES_LICENSE, NECTAR_LICENSE),
        OC(ASYNC_HTTP_CLIENT, CLOUDBEES_LICENSE, NECTAR_LICENSE, OC_AGENT, OC_CONTEXT, OC_CLIENT, OC_CLOUD,
                OC_OPENID_CSE, OC_ANALYTICS),
        FULL(
            require("metrics","3.0.11"), // put this first
            require("support-core","2.25"), // put this second
            CLOUDBEES_LICENSE, // put this third
            require("cloudbees-support", "3.3"), // put this fourth
            ASYNC_HTTP_CLIENT,
            NECTAR_LICENSE,
            OC_AGENT, OC_CONTEXT, OC_CLIENT, OC_CLOUD, OC_OPENID_CSE, OC_ANALYTICS,
            require("workflow-aggregator", WORKFLOW_VERSION),
            require("workflow-api", WORKFLOW_VERSION),
            require("workflow-basic-steps", WORKFLOW_VERSION),
            require("workflow-cps", WORKFLOW_VERSION),
            require("workflow-cps-global-lib", WORKFLOW_VERSION),
            require("workflow-durable-task-step", WORKFLOW_VERSION),
            require("workflow-job", WORKFLOW_VERSION),
            require("workflow-scm-step", WORKFLOW_VERSION),
            require("workflow-step-api", WORKFLOW_VERSION),
            require("workflow-support", WORKFLOW_VERSION),
            require("cloudbees-workflow-aggregator", CLOUDBEES_WORKFLOW_VERSION),
            require("cloudbees-workflow-rest-api", CLOUDBEES_WORKFLOW_VERSION),
            require("cloudbees-workflow-template", CLOUDBEES_WORKFLOW_VERSION),
            require("cloudbees-workflow-ui", CLOUDBEES_WORKFLOW_VERSION),
            require("workflow-cps-checkpoint", CLOUDBEES_WORKFLOW_VERSION),
            require("cloudbees-ha","4.6"),
            // The rest can be generated by list-enterprise-plugins.rb:
            require("cloudbees-aborted-builds","1.6"),
            require("cloudbees-aws-cli","1.4"),
            require("cloudbees-aws-credentials","1.7"),
            require("cloudbees-aws-deployer","1.13"),
            require("cloudbees-consolidated-build-view","1.4"),
            require("cloudbees-even-scheduler","3.5"),
            require("cloudbees-folder","4.8"),
            require("cloudbees-folders-plus","2.10"),
            require("cloudbees-groovy-view","1.4"),
            require("cloudbees-jsync-archiver","5.4"),
            require("cloudbees-label-throttling-plugin","3.4"),
            require("cloudbees-long-running-build","1.4"),
            require("cloudbees-monitoring","1.7"),
            require("cloudbees-nodes-plus","1.11"),
            require("cloudbees-plugin-usage","1.5"),
            require("cloudbees-quiet-start","1.1"),
            require("cloudbees-secure-copy","3.7"),
            require("cloudbees-ssh-slaves","1.2"),
            require("cloudbees-template","4.17"),
            require("cloudbees-view-creation-filter","1.2"),
            require("cloudbees-wasted-minutes-tracker","3.7"),
            require("copyartifact","1.35.1"),
            require("credentials","1.22"),
            require("dashboard-view","2.9.4"),
            require("docker-build-publish","1.0"),
            require("docker-commons","1.0"),
            require("docker-traceability","1.0"),
            require("docker-workflow","1.0"),
            require("dockerhub-notification","1.0.2"),
            require("durable-task","1.5"),
            require("external-monitor-job","1.4"),
            require("git","2.3.5"),
            require("git-client","1.17.1"),
            require("git-server","1.6"),
            require("git-validated-merge","3.18"),
            require("github","1.11.3"),
            require("github-api","1.68"),
            require("github-pull-request-build","1.6"),
            require("infradna-backup","3.20"),
            require("javadoc","1.3"),
            require("junit","1.6"),
            require("ldap","1.11"),
            require("mailer","1.15"),
            require("matrix-auth","1.2"),
            require("matrix-project","1.5"),
            require("maven-plugin","2.10"),
            require("mercurial","1.52"),
            require("monitoring","1.56.0"),
            require("nectar-rbac","4.15"),
            require("nectar-vmware","4.3.4"),
            require("node-iterator-api","1.5"),
            require("openid","2.1.1"),
            require("openid4java","0.9.8.0"),
            require("pam-auth","1.2"),
            require("parameterized-trigger","2.26"),
            require("promoted-builds","2.21"),
            require("scm-api","0.2"),
            require("script-security","1.14"),
            require("skip-plugin","3.6"),
            require("ssh-agent","1.7"),
            require("ssh-credentials","1.11"),
            require("ssh-slaves","1.9"),
            require("suppress-stack-trace","1.3"),
            require("token-macro","1.10"),
            require("translation","1.12"),
            require("unique-id","2.0.2"),
            require("wikitext","3.6")
        );
        /**
         * The plugins that can and/or should be installed/upgraded.
         */
        public final Dependency[] dependencies;
        private InstallMode(Dependency... dependencies) {
            this.dependencies = dependencies;
        }
    }

    /**
     * The list of plugin installations that remains to be completed.
     * <p/>
     * Guarded by {@link #pendingPluginInstalls}.
     */
    private static final List<Dependency> pendingPluginInstalls = new ArrayList<Dependency>();

    /**
     * Guarded by {@link #pendingPluginInstalls}.
     */
    private static DelayedInstaller worker = null;

    /**
     * The current status.
     */
    private static volatile Localizable status = null;

    /**
     * The most recently installed version of this plugin, used to trigger whether to re-evaluate installing/upgrading
     * the {@link #CLOUDBEES_PLUGINS}.
     */
    private String installedVersion = null;

    public PluginImpl() {
    }

    @Override
    public void start() throws Exception {
        LOGGER.log(Level.INFO, "Started...");
        try {
            load();
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "Could not deserialize state, assuming the plugins need re-installation", e);
            installedVersion = null;
        }
    }

    public boolean isInstalled() {
        if (installedVersion == null) {
            return false;
        }
        try {
            PluginWrapper pluginWrapper = Jenkins.getInstance().getPluginManager().getPlugin(getClass());
            String targetVersion = getVersionString(pluginWrapper);
            LOGGER.log(Level.FINE, "Installed version = {0}. Target version = {1}",
                    new Object[]{installedVersion, targetVersion});
            return !new VersionNumber(installedVersion).isOlderThan(new VersionNumber(targetVersion));
        } catch (Throwable t) {
            // if in doubt, it's not installed
            return false;
        }
    }

    public void setInstalled(boolean installed) {
        boolean changed = false;
        if (installed) {
            PluginWrapper pluginWrapper = Jenkins.getInstance().getPluginManager().getPlugin(getClass());
            String version = getVersionString(pluginWrapper);
            if (!version.equals(installedVersion)) {
                this.installedVersion = version;
                changed = true;
            }
        } else {
            if (installedVersion != null) {
                installedVersion = null;
                changed = true;
            }
        }
        if (changed) {
            try {
                save();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Could not serialize state. If any of the free plugins are uninstalled, "
                                + "they may be reinstalled on next restart.",
                        e);
            }
        }
    }

    private String getVersionString(PluginWrapper pluginWrapper) {
        String version = pluginWrapper.getVersionNumber().toString();
        int i = version.indexOf(' ');
        version = i == -1 ? version : version.substring(0, i);
        return version;
    }

    public static Localizable getStatus() {
        return status;
    }

    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED)
    public static void removeUpdateCenter() throws Exception {
        LOGGER.log(Level.FINE, "Checking whether the official CloudBees update center has been configured.");
        UpdateCenter updateCenter = Jenkins.getInstance().getUpdateCenter();
        synchronized (updateCenter) {
            PersistedList<UpdateSite> sites = updateCenter.getSites();
            PluginWrapper plugin = Jenkins.getInstance().getPluginManager().getPlugin("nectar-license");
            if (plugin != null && plugin.isActive()) {
                // delegate to nectar-license once it is installed and active
                List<UpdateSite> forRemoval = new ArrayList<UpdateSite>();
                for (UpdateSite site : sites) {
                    if (site instanceof CloudBeesUpdateSite) {
                        forRemoval.add(site);
                    }
                }

                // now make the changes if we have any to make
                if (!forRemoval.isEmpty()) {
                    BulkChange bc = new BulkChange(updateCenter);
                    try {
                        for (UpdateSite site : forRemoval) {
                            sites.remove(site);
                        }
                    } finally {
                        bc.commit();
                    }
                }
            }
        }
    }

    private static void addUpdateCenter() throws Exception {
        status = Messages._Notice_downloadUCMetadata();
        LOGGER.log(Level.FINE, "Checking that the CloudBees update center has been configured.");
        UpdateCenter updateCenter = Jenkins.getInstance().getUpdateCenter();
        synchronized (updateCenter) {
            PersistedList<UpdateSite> sites = updateCenter.getSites();
                if (sites.isEmpty()) {
                    // likely the list has not been loaded yet
                    updateCenter.load();
                    sites = updateCenter.getSites();
                }

                boolean found = false;
                List<UpdateSite> forRemoval = new ArrayList<UpdateSite>();
                for (UpdateSite site : sites) {
                    LOGGER.log(Level.FINEST, "Update site {0} class {1} url {2}",
                            new Object[]{site.getId(), site.getClass(), site.getUrl()});
                    if (cloudBeesUpdateCenterUrls.contains(site.getUrl()) || cloudBeesUpdateCenterIds
                            .contains(site.getId())
                            || site instanceof CloudBeesUpdateSite) {
                        LOGGER.log(Level.FINE, "Found possible match:\n  class = {0}\n  url = {1}\n  id = {2}",
                                new Object[]{site.getClass().getName(), site.getUrl(), site.getId()});
                        boolean valid = site instanceof CloudBeesUpdateSite
                                && CLOUDBEES_UPDATE_CENTER_URL.equals(site.getUrl())
                                && CLOUDBEES_UPDATE_CENTER_ID.equals(site.getId());
                        if (found || !valid) {
                            // remove old and duplicate entries
                            forRemoval.add(site);
                        }
                        found = found || valid;
                    }
                }

                // now make the changes if we have any to make
                LOGGER.log(Level.FINE, "Found={0}\nRemoving={1}", new Object[]{found, forRemoval});
                if (!found || !forRemoval.isEmpty()) {
                    BulkChange bc = new BulkChange(updateCenter);
                    try {
                        for (UpdateSite site : forRemoval) {
                            LOGGER.info("Removing legacy CloudBees Update Center from list of update centers");
                            sites.remove(site);
                        }
                        if (sites.isEmpty()) {
                            LOGGER.info("Adding Default Update Center to list of update centers as it was missing");
                            sites.add(new UpdateSite("default", "http://updates.jenkins-ci.org/update-center.json"));
                        }
                        if (!found) {
                            LOGGER.info("Adding CloudBees Update Center to list of update centers");
                            sites.add(new CloudBeesUpdateSite(CLOUDBEES_UPDATE_CENTER_ID, CLOUDBEES_UPDATE_CENTER_URL));
                        }
                    } finally {
                        bc.commit();
                    }
                    LOGGER.info("Refreshing modified update center list");
                    for (FormValidation result : updateCenter.updateAllSites()) {
                        if (result.kind != FormValidation.Kind.OK) {
                            LOGGER.log(Level.WARNING, "Failed to refresh update center: {0}", result);
                        }
                    }
                }
            }
    }

    public static boolean isEverythingInstalled() {
        PluginImpl instance = Jenkins.getInstance().getPlugin(PluginImpl.class);
        if (instance != null && instance.isInstalled()) {
            for (Dependency pluginArtifactId : InstallMode.FULL.dependencies) {
                if (pluginArtifactId.mandatory) {
                    LOGGER.log(Level.FINE, "Checking {0}.", pluginArtifactId.name);
                    PluginWrapper plugin = Jenkins.getInstance().getPluginManager().getPlugin(pluginArtifactId.name);
                    if (plugin == null) {
                        return false;
                    }
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public static void installPlugins(InstallMode installMode) throws Exception {
        addUpdateCenter();
        LOGGER.log(Level.INFO, "Checking that the CloudBees plugins have been installed.");
        for (Dependency pluginArtifactId : installMode.dependencies) {
            LOGGER.log(Level.FINE, "Checking {0}.", pluginArtifactId.name);
            PluginWrapper plugin = Jenkins.getInstance().getPluginManager().getPlugin(pluginArtifactId.name);
            if (plugin == null && !pluginArtifactId.optional) {
                // not installed and mandatory
                scheduleInstall(pluginArtifactId);
            } else if (plugin != null && (pluginArtifactId.version != null || plugin.getVersion() == null)) {
                // already installed
                if (plugin.getVersionNumber().compareTo(pluginArtifactId.version) < 0) {
                    // but older version
                    scheduleInstall(pluginArtifactId);
                }
            }
            if (pluginArtifactId.mandatory) {
                if (plugin != null && !plugin.isEnabled()) {
                    LOGGER.log(Level.FINE, "Enabling {0}", pluginArtifactId.name);
                    try {
                        plugin.enable();
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Could not enable " + pluginArtifactId.name, e);
                    }
                }
            }
        }
        boolean finished;
        synchronized (pendingPluginInstalls) {
            finished = pendingPluginInstalls.isEmpty();
            if (!finished && (worker == null || !worker.isAlive())) {
                LOGGER.info("Starting background thread for core plugin installation");
                worker = new DelayedInstaller();
                worker.setDaemon(true);
                worker.start();
            } else {
                LOGGER.log(Level.INFO, "Nothing to do");
            }
        }
        PluginImpl instance = Jenkins.getInstance().getPlugin(PluginImpl.class);
        if (finished && instance != null) {
            instance.setInstalled(true);
        }
    }

    private static void scheduleInstall(Dependency pluginArtifactId) {
        synchronized (pendingPluginInstalls) {
            LOGGER.log(Level.FINE, "Scheduling installation of {0}", pluginArtifactId.name);
            pendingPluginInstalls.add(pluginArtifactId);
        }
    }

    private static class DelayedInstaller extends Thread {

        private long nextWarning;

        @Override
        public void run() {
            nextWarning = 0;
            try {
                boolean loop = true;
                while (loop) {
                    LOGGER.fine("Background thread for core plugin installation awake");
                    try {
                        UpdateSite cloudbeesSite =
                                Jenkins.getInstance().getUpdateCenter().getSite(CLOUDBEES_UPDATE_CENTER_ID);
                        if (cloudbeesSite.getDataTimestamp() > -1) {
                            loop = progressPluginInstalls();
                        } else {
                            status = Messages._Notice_downloadUCMetadata();
                        }

                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        // ignore
                    } catch (Throwable t) {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            // ignore
                        }
                    }
                }
                if (!loop) {
                    try {
                        status = Messages._Notice_scheduledRestart();
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            // ignore
                        }
                        Jenkins.getInstance().safeRestart();
                        // if the user manually cancelled the quiet down, reflect that in the status message
                        Timer.get().scheduleAtFixedRate(new SafeTimerTask() {
                            @Override
                            protected void doRun() throws Exception {
                                if (!Jenkins.getInstance().isQuietingDown()) {
                                    status = null;
                                }
                            }
                        }, 1000, 1000, TimeUnit.MILLISECONDS);
                    } catch (RestartNotSupportedException exception) {
                        // ignore if restart is not allowed
                        status = Messages._Notice_restartRequired();
                    }
                }
            } finally {
                LOGGER.info("Background thread for core plugin installation finished.");
                boolean finished;
                synchronized (pendingPluginInstalls) {
                    if (worker == this) {
                        worker = null;
                    }
                    finished = pendingPluginInstalls.isEmpty();
                }
                PluginImpl instance = Jenkins.getInstance().getPlugin(PluginImpl.class);
                if (finished && instance != null) {
                    instance.setInstalled(true);
                }
            }
        }

        private boolean progressPluginInstalls() {
            synchronized (pendingPluginInstalls) {
                while (!pendingPluginInstalls.isEmpty()) {
                    Dependency pluginArtifactId = pendingPluginInstalls.get(0);
                    UpdateSite.Plugin p = Jenkins.getInstance()
                            .getUpdateCenter()
                            .getSite(CLOUDBEES_UPDATE_CENTER_ID)
                            .getPlugin(pluginArtifactId.name);
                    if (p == null) {
                        if (System.currentTimeMillis() > nextWarning) {
                            LOGGER.log(Level.WARNING,
                                    "Cannot find core plugin {0}, the CloudBees free plugins cannot be "
                                            + "installed without this core plugin. Will try again later.",
                                    pluginArtifactId.name);
                            nextWarning = System.currentTimeMillis() + TimeUnit2.HOURS.toMillis(1);
                        }
                        break;
                    } else if (p.getInstalled() != null && p.getInstalled().isEnabled()) {
                        PluginWrapper plugin = Jenkins.getInstance().getPluginManager().getPlugin(pluginArtifactId.name);
                        if (plugin != null && plugin.getVersionNumber().compareTo(pluginArtifactId.version) < 0) {
                            LOGGER.log(Level.INFO, "Upgrading CloudBees plugin: {0}", pluginArtifactId.name);
                            status = Messages._Notice_upgradingPlugin(p.getDisplayName(), p.version);
                            SecurityContext old = ACL.impersonate(ACL.SYSTEM);
                            try {
                                p.deploy().get();
                                LOGGER.log(Level.INFO, "Upgraded CloudBees plugin: {0} to {1}", new Object[] {pluginArtifactId.name, p.version});
                                pendingPluginInstalls.remove(0);
                                nextWarning = 0;
                                status = Messages._Notice_upgradedPlugin(p.getDisplayName(), p.version);
                            } catch (Throwable e) {
                                if (System.currentTimeMillis() > nextWarning) {
                                    LOGGER.log(Level.WARNING,
                                            "Cannot upgrade CloudBees plugin: " + pluginArtifactId.name + " to "
                                                    + p.version, e);
                                    nextWarning = System.currentTimeMillis() + TimeUnit2.MINUTES.toMillis(1);
                                }
                                break;
                            } finally {
                                SecurityContextHolder.setContext(old);
                            }
                        } else {
                            LOGGER.log(Level.INFO, "Detected previous installation of CloudBees plugin: {0}", pluginArtifactId.name);
                            pendingPluginInstalls.remove(0);
                            nextWarning = 0;
                        }
                    } else {
                        LOGGER.log(Level.INFO, "Installing CloudBees plugin: {0} version {1}", new Object[] {pluginArtifactId.name, p.version});
                        status = Messages._Notice_installingPlugin(p.getDisplayName());
                        SecurityContext old = ACL.impersonate(ACL.SYSTEM);
                        try {
                            p.deploy().get();
                            LOGGER.log(Level.INFO, "Installed CloudBees plugin: {0} version {1}", new Object[] {pluginArtifactId.name, p.version});
                            pendingPluginInstalls.remove(0);
                            nextWarning = 0;
                            status = Messages._Notice_installedPlugin(p.getDisplayName());
                        } catch (Throwable e) {
                            if (System.currentTimeMillis() > nextWarning) {
                                LOGGER.log(Level.WARNING,
                                        "Cannot install CloudBees plugin: " + pluginArtifactId.name + " version "
                                                + p.version, e);
                                nextWarning = System.currentTimeMillis() + TimeUnit2.MINUTES.toMillis(1);
                            }
                            break;
                        } finally {
                            SecurityContextHolder.setContext(old);
                        }
                    }
                }
                return !pendingPluginInstalls.isEmpty();
            }
        }
    }

    private static Dependency require(String name) {
        return require(name, null);
    }

    private static Dependency require(String name, String version) {
        return new Dependency(name, version, false, true);
    }

    // TODO seems we have no optional plugins; could this logic just be deleted?
    private static Dependency optional(String name) {
        return optional(name, null);
    }

    private static Dependency optional(String name, String version) {
        return new Dependency(name, version, true, false);
    }

    private static class Dependency {
        public final String name;
        public final VersionNumber version;
        public final boolean optional;
        public final boolean mandatory;

        private Dependency(String name, String version, boolean optional, boolean mandatory) {
            this.name = name;
            this.version = version == null ? null : new VersionNumber(version);
            this.optional = optional;
            this.mandatory = mandatory;
        }

    }
}
