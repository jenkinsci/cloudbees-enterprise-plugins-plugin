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
import hudson.model.PageDecorator;
import hudson.model.RootAction;
import org.jvnet.localizer.Localizable;

/**
 * Displays the enterprise plugins progress notices
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

    public boolean isImportant() {
        return PluginImpl.isStatusImportant();
    }

    public static Notice getInstanceOrDie() {
        for (PageDecorator decorator : PageDecorator.all()) {
            if (decorator instanceof Notice) {
                return (Notice) decorator;
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