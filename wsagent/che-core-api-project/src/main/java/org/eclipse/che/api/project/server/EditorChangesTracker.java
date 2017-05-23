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

import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.ForbiddenException;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.jsonrpc.commons.JsonRpcException;
import org.eclipse.che.api.core.jsonrpc.commons.RequestHandlerConfigurator;
import org.eclipse.che.api.project.shared.dto.EditorChangesDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;

/**
 * Receives notifications about editor changes from client side.
 *
 * @author Roman Nikitenko
 */
@Singleton
public class EditorChangesTracker {
    private static final Logger LOG             = LoggerFactory.getLogger(EditorWorkingCopyManager.class);
    private static final String INCOMING_METHOD = "track:editor-content-changes";

    private EditorWorkingCopyManager editorWorkingCopyManager;

    @Inject
    public EditorChangesTracker(EditorWorkingCopyManager editorWorkingCopyManager) {
        this.editorWorkingCopyManager = editorWorkingCopyManager;
    }

    @Inject
    public void configureHandler(RequestHandlerConfigurator configurator) {
        configurator.newConfiguration()
                    .methodName(INCOMING_METHOD)
                    .paramsAsDto(EditorChangesDto.class)
                    .resultAsEmpty()
                    .withFunction(this::onEditorContentChange);
    }

    private Void onEditorContentChange(String endpointId, EditorChangesDto changes) {
        String filePath = changes.getFileLocation();
        String projectPath = changes.getProjectPath();

        try {
            if (filePath.isEmpty() || projectPath.isEmpty()) {
                throw new NotFoundException("Paths for file and project should be defined");
            }

            editorWorkingCopyManager.onEditorContentUpdated(endpointId, changes);

        } catch (IOException | ForbiddenException | ConflictException | ServerException e) {
            String errorMessage = "Can not handle editor changes: " + e.getLocalizedMessage();

            LOG.error(errorMessage);

            throw new JsonRpcException(500, errorMessage);
        } catch (NotFoundException e) {
            String errorMessage = "Can not handle editor changes: " + e.getLocalizedMessage();

            LOG.error(errorMessage);

            throw new JsonRpcException(400, errorMessage);
        }
        return null;
    }
}
