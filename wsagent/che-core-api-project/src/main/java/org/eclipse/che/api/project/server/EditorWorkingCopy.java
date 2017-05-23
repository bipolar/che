/*******************************************************************************
 * Copyright (c) 2012-2017 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.api.project.server;

/**
 * In-memory implementation of working copy for opened editor on client.
 *
 * @author Roman Nikitenko
 */
public class EditorWorkingCopy {

    private String path;
    private String projectPath;
    private byte[]            content;

    /**
     * Creates a working copy for opened editor on client.
     *
     * @param path
     *         path to the persistent working copy
     * @param projectPath
     *         path to the project which contains the opened editor on client
     * @param content
     *         the content of the original file to creating working copy
     */
    public EditorWorkingCopy(String path, String projectPath, byte[] content) {
        this.path = path;
        this.projectPath = projectPath;
        this.content = content;
    }

    /**
     * Gets content of the working copy as bytes.
     *
     * @return content ot the working copy
     */
    public byte[] getContentAsBytes() {
        return content;
    }

    /**
     * Gets content of the working copy as String decoding bytes.
     *
     * @return content ot the working copy
     */
    public String getContentAsString() {
        return new String(content);
    }

    /**
     * Updates content of the working copy.
     *
     * @param content
     *         content
     * @return current working copy after updating content
     */
    public EditorWorkingCopy updateContent(byte[] content) {
        this.content = content;
        return this;
    }

    /**
     * Updates content of the working copy.
     *
     * @param content
     *         content
     * @return current working copy after updating content
     */
    public EditorWorkingCopy updateContent(String content) {
        this.content = content.getBytes();
        return this;
    }

    /** Returns the path to the persistent working copy */
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    /** Returns the path to the project which contains the opened editor on client */
    public String getProjectPath() {
        return projectPath;
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }
}
