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

import com.google.common.hash.Hashing;

import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.ForbiddenException;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.jsonrpc.commons.RequestTransmitter;
import org.eclipse.che.api.core.notification.EventService;
import org.eclipse.che.api.core.notification.EventSubscriber;
import org.eclipse.che.api.project.shared.dto.EditorChangesDto;
import org.eclipse.che.api.project.shared.dto.EditorChangesDto.Type;
import org.eclipse.che.api.project.shared.dto.ServerError;
import org.eclipse.che.api.project.shared.dto.event.FileTrackingOperationDto;
import org.eclipse.che.api.vfs.impl.file.event.detectors.FileTrackingOperationEvent;
import org.eclipse.che.dto.server.DtoFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.String.format;
import static java.nio.charset.Charset.defaultCharset;
import static org.eclipse.che.api.project.shared.Constants.CHE_DIR;
import static org.eclipse.che.api.project.shared.dto.EditorChangesDto.Type.INSERT;
import static org.eclipse.che.api.project.shared.dto.EditorChangesDto.Type.REMOVE;

/**
 * The class contains methods to simplify the work with editor working copies.
 *
 * @author Roman Nikitenko
 */
@Singleton
public class EditorWorkingCopyManager {
    private static final Logger LOG                       = LoggerFactory.getLogger(EditorWorkingCopyManager.class);
    private static final String WORKING_COPIES_DIR        = "/" + CHE_DIR + "/workingCopies";
    private static final String WORKING_COPY_ERROR_METHOD = "track:editor-working-copy-error";

    private Provider<ProjectManager>                    projectManagerProvider;
    private EventService                                eventService;
    private RequestTransmitter                          transmitter;
    private EventSubscriber<FileTrackingOperationEvent> fileOperationEventSubscriber;

    private final Map<String, EditorWorkingCopy> workingCopiesStorage = new ConcurrentHashMap<>();

    @Inject
    public EditorWorkingCopyManager(Provider<ProjectManager> projectManagerProvider,
                                    EventService eventService,
                                    RequestTransmitter transmitter) {
        this.projectManagerProvider = projectManagerProvider;
        this.eventService = eventService;
        this.transmitter = transmitter;

        fileOperationEventSubscriber = new EventSubscriber<FileTrackingOperationEvent>() {
            @Override
            public void onEvent(FileTrackingOperationEvent event) {
                onFileOperation(event.getEndpointId(), event.getFileTrackingOperation());
            }
        };
        eventService.subscribe(fileOperationEventSubscriber);
    }

    /**
     * Gets persistent working copy by path to the original file.
     * Note: returns {@code null} when working copy is not found
     *
     * @param filePath
     *         path to the original file
     * @return persistent working copy for the file which corresponds given {@code filePath} or {@code null} when working copy is not found
     * @throws NotFoundException
     *         if file is not found by given {@code filePath}
     * @throws ForbiddenException
     *         if item is not a file
     * @throws ServerException
     *         if other error occurs
     */
    public File getPersistentWorkingCopy(String filePath) throws NotFoundException, ServerException, ForbiddenException {
        VirtualFileEntry persistentWorkingCopy = null;
        if (workingCopiesStorage.containsKey(filePath)) {
            EditorWorkingCopy workingCopy = workingCopiesStorage.get(filePath);
            byte[] workingCopyContent = workingCopy.getContentAsBytes();

            persistentWorkingCopy = getPersistentWorkingCopy(filePath, workingCopy.getProjectPath());
            persistentWorkingCopy.getVirtualFile().updateContent(workingCopyContent);
        } else {
            FileEntry originalFile = projectManagerProvider.get().asFile(filePath);
            if (originalFile != null) {
                persistentWorkingCopy = getPersistentWorkingCopy(filePath, originalFile.getProject());
            }
        }
        return persistentWorkingCopy == null ? null : new File(persistentWorkingCopy.getVirtualFile().toIoFile().getAbsolutePath());
    }

    /**
     * Gets content of the working copy as String decoding bytes.
     * Note: gets content of the original file if working copy is not found
     *
     * @param filePath
     *         path to the original file
     * @return content of the working copy for the file which corresponds given {@code filePath}
     * or content of the file if working copy is not found
     * @throws NotFoundException
     *         if file is not found by given {@code filePath}
     * @throws ForbiddenException
     *         if item is not a file
     * @throws ServerException
     *         if other error occurs
     */
    public String getContentFor(String filePath) throws NotFoundException, ServerException, ForbiddenException {
        EditorWorkingCopy workingCopy = workingCopiesStorage.get(filePath);
        if (workingCopy != null) {
            return workingCopy.getContentAsString();
        }

        FileEntry originalFile = projectManagerProvider.get().asFile(filePath);
        if (originalFile == null) {
            throw new NotFoundException(format("File '%s' isn't found. ", filePath));
        }
        return originalFile.getVirtualFile().getContentAsString();
    }

    void onEditorContentUpdated(String endpointId, EditorChangesDto changes)
            throws IOException, ForbiddenException, ConflictException, NotFoundException, ServerException {

        String filePath = changes.getFileLocation();
        EditorWorkingCopy workingCopy = workingCopiesStorage.get(filePath);
        if (workingCopy == null) {
            workingCopy = createWorkingCopy(filePath);
        }

        String text = changes.getText();
        int offset = changes.getOffset();
        int removedCharCount = changes.getRemovedCharCount();

        String newContent = null;
        String oldContent = workingCopy.getContentAsString();
        Type type = changes.getType();
        if (type == INSERT) {
            newContent = new StringBuilder(oldContent).insert(offset, text).toString();
        }

        if (type == REMOVE && removedCharCount > 0) {
            newContent = new StringBuilder(oldContent).delete(offset, offset + removedCharCount).toString();
        }

        if (newContent != null) {
            workingCopy.updateContent(newContent);
            eventService.publish(new EditorWorkingCopyUpdatedEvent(endpointId, changes));
        }
    }

    private void onFileOperation(String endpointId, FileTrackingOperationDto operation) {
        try {
            FileTrackingOperationDto.Type type = operation.getType();
            switch (type) {
                case START: {
                    String path = operation.getPath();
                    EditorWorkingCopy workingCopy = workingCopiesStorage.get(path);
                    if (workingCopy == null) {
                        createWorkingCopy(path);
                    }
                    break;
                }
                case STOP: {
                    String path = operation.getPath();
                    EditorWorkingCopy workingCopy = workingCopiesStorage.get(path);
                    if (workingCopy == null) {
                        return;
                    }

                    if (isWorkingCopyHasUnsavedData(path)) {
                        updatePersistentWorkingCopy(path);//to have ability to recover unsaved data when the file will be open later
                    } else {
                        VirtualFileEntry persistentWorkingCopy = getPersistentWorkingCopy(path, workingCopy.getProjectPath());
                        if (persistentWorkingCopy != null) {
                            persistentWorkingCopy.remove();
                        }
                    }
                    workingCopiesStorage.remove(path);
                    break;
                }

                case MOVE: {
                    String oldPath = operation.getOldPath();
                    String newPath = operation.getPath();

                    EditorWorkingCopy workingCopy = workingCopiesStorage.remove(oldPath);
                    if (workingCopy == null) {
                        return;
                    }

                    String workingCopyNewPath = toWorkingCopyPath(newPath);
                    workingCopy.setPath(workingCopyNewPath);
                    workingCopiesStorage.put(newPath, workingCopy);

                    String projectPath = workingCopy.getProjectPath();
                    VirtualFileEntry persistentWorkingCopy = getPersistentWorkingCopy(oldPath, projectPath);
                    if (persistentWorkingCopy != null) {
                        persistentWorkingCopy.remove();
                    }

                    FolderEntry persistentWorkingCopiesStorage = getPersistentWorkingCopiesStorage(projectPath);
                    if (persistentWorkingCopiesStorage == null) {
                        persistentWorkingCopiesStorage = createWorkingCopiesStorage(projectPath);
                    }

                    persistentWorkingCopiesStorage.createFile(workingCopyNewPath, workingCopy.getContentAsBytes());
                    break;
                }

                default: {
                    break;
                }
            }
        } catch (ServerException | IOException | ForbiddenException | ConflictException e) {
            String errorMessage = "Can not handle file operation: " + e.getMessage();

            LOG.error(errorMessage);

            transmitError(500, errorMessage, endpointId);
        } catch (NotFoundException e) {
            String errorMessage = "Can not handle file operation: " + e.getMessage();

            LOG.error(errorMessage);

            transmitError(400, errorMessage, endpointId);
        }
    }

    private void transmitError(int code, String errorMessage, String endpointId) {
        DtoFactory dtoFactory = DtoFactory.getInstance();
        ServerError error = dtoFactory.createDto(ServerError.class)
                                      .withCode(code)
                                      .withMessage(errorMessage);
        transmitter.newRequest()
                   .endpointId(endpointId)
                   .methodName(WORKING_COPY_ERROR_METHOD)
                   .paramsAsDto(error)
                   .sendAndSkipResult();
    }

    private void updatePersistentWorkingCopy(String originalFilePath) throws ServerException, ForbiddenException, ConflictException {
        EditorWorkingCopy workingCopy = workingCopiesStorage.get(originalFilePath);
        if (workingCopy == null) {
            return;
        }

        byte[] content = workingCopy.getContentAsBytes();
        String projectPath = workingCopy.getProjectPath();

        VirtualFileEntry persistentWorkingCopy = getPersistentWorkingCopy(originalFilePath, projectPath);
        if (persistentWorkingCopy != null) {
            persistentWorkingCopy.getVirtualFile().updateContent(content);
            return;
        }

        FolderEntry persistentWorkingCopiesStorage = getPersistentWorkingCopiesStorage(projectPath);
        if (persistentWorkingCopiesStorage == null) {
            persistentWorkingCopiesStorage = createWorkingCopiesStorage(projectPath);
        }

        persistentWorkingCopiesStorage.createFile(workingCopy.getPath(), content);
    }

    private boolean isWorkingCopyHasUnsavedData(String originalFilePath) {
        try {
            EditorWorkingCopy workingCopy = workingCopiesStorage.get(originalFilePath);
            if (workingCopy == null) {
                return false;
            }

            FileEntry originalFile = projectManagerProvider.get().asFile(originalFilePath);
            if (originalFile == null) {
                return false;
            }

            String workingCopyContent = workingCopy.getContentAsString();
            String originalFileContent = originalFile.getVirtualFile().getContentAsString();
            if (workingCopyContent == null || originalFileContent == null) {
                return false;
            }

            String workingCopyHash = Hashing.md5().hashString(workingCopyContent, defaultCharset()).toString();
            String originalFileHash = Hashing.md5().hashString(originalFileContent, defaultCharset()).toString();

            return !Objects.equals(workingCopyHash, originalFileHash);
        } catch (NotFoundException | ServerException | ForbiddenException e) {
            LOG.error(e.getLocalizedMessage());
        }

        return false;
    }

    private EditorWorkingCopy createWorkingCopy(String filePath)
            throws NotFoundException, ServerException, ConflictException, ForbiddenException, IOException {

        FileEntry file = projectManagerProvider.get().asFile(filePath);
        if (file == null) {
            throw new NotFoundException(format("Item '%s' isn't found. ", filePath));
        }

        String projectPath = file.getProject();
        FolderEntry persistentWorkingCopiesStorage = getPersistentWorkingCopiesStorage(projectPath);
        if (persistentWorkingCopiesStorage == null) {
            persistentWorkingCopiesStorage = createWorkingCopiesStorage(projectPath);
        }

        String workingCopyPath = toWorkingCopyPath(filePath);
        byte[] content = file.contentAsBytes();

        VirtualFileEntry persistentWorkingCopy = persistentWorkingCopiesStorage.getChild(workingCopyPath);
        if (persistentWorkingCopy == null) {
            persistentWorkingCopiesStorage.createFile(workingCopyPath, content);
        } else {
            //TODO
            //At opening file we can have persistent working copy ONLY when user has unsaved data
            // at this case we need provide ability to recover unsaved data, so update content is temporary solution
            persistentWorkingCopy.getVirtualFile().updateContent(content);
        }

        EditorWorkingCopy workingCopy = new EditorWorkingCopy(workingCopyPath, projectPath, file.contentAsBytes());
        workingCopiesStorage.put(filePath, workingCopy);

        return workingCopy;
    }

    private VirtualFileEntry getPersistentWorkingCopy(String originalFilePath, String projectPath) {
        try {
            FolderEntry persistentWorkingCopiesStorage = getPersistentWorkingCopiesStorage(projectPath);
            if (persistentWorkingCopiesStorage == null) {
                return null;
            }

            String workingCopyPath = toWorkingCopyPath(originalFilePath);
            return persistentWorkingCopiesStorage.getChild(workingCopyPath);
        } catch (ServerException e) {
            return null;
        }
    }

    private FolderEntry getPersistentWorkingCopiesStorage(String projectPath) {
        try {
            RegisteredProject project = projectManagerProvider.get().getProject(projectPath);
            FolderEntry baseFolder = project.getBaseFolder();
            if (baseFolder == null) {
                return null;
            }

            String tempDirectoryPath = baseFolder.getPath().toString() + WORKING_COPIES_DIR;
            return projectManagerProvider.get().asFolder(tempDirectoryPath);
        } catch (Exception e) {
            return null;
        }
    }

    private FolderEntry createWorkingCopiesStorage(String projectPath) {
        try {
            RegisteredProject project = projectManagerProvider.get().getProject(projectPath);
            FolderEntry baseFolder = project.getBaseFolder();
            if (baseFolder == null) {
                return null;
            }

            return baseFolder.createFolder(WORKING_COPIES_DIR);
        } catch (Exception e) {
            return null;
        }
    }

    private String toWorkingCopyPath(String path) {
        if (path.startsWith("/")) {
            path = path.substring(1, path.length());
        }
        return path.replace('/', '.');
    }

    @PreDestroy
    private void unsubscribe() {
        eventService.unsubscribe(fileOperationEventSubscriber);
    }
}
