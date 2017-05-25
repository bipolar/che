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

import com.google.common.io.ByteStreams;

import org.eclipse.che.api.core.ServerException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static java.lang.String.format;

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
     * Gets content of the working copy.
     *
     * @return content ot the working copy
     */
    public InputStream getContent() {
        return new ByteArrayInputStream(getContentAsBytes());
    }

    /**
     * Updates content of the working copy.
     *
     * @param content
     *         content
     * @return current working copy after updating content
     */
    EditorWorkingCopy updateContent(byte[] content) {
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
    EditorWorkingCopy updateContent(String content) {
        this.content = content.getBytes();
        return this;
    }

    /**
     * Updates content of the working copy.
     *
     * @param content
     *         content
     * @return current working copy after updating content
     */
    EditorWorkingCopy updateContent(InputStream content) throws ServerException {
        byte[] bytes;
        try {
            bytes = ByteStreams.toByteArray(content);
        } catch (IOException e) {
            throw new ServerException(format("Can not update the content of '%s'. The reason is: %s", getPath(), e.getMessage()));
        }
        return updateContent(bytes);
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
