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
package org.eclipse.che.ide.editor.orion.client;

import com.google.gwt.user.client.Timer;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;

import org.eclipse.che.ide.api.editor.EditorOpenedEvent;
import org.eclipse.che.ide.api.editor.EditorOpenedEventHandler;
import org.eclipse.che.ide.api.editor.EditorPartPresenter;
import org.eclipse.che.ide.api.editor.document.DocumentHandle;
import org.eclipse.che.ide.api.editor.events.DocumentChangeEvent;
import org.eclipse.che.ide.api.editor.reconciler.DirtyRegion;
import org.eclipse.che.ide.api.editor.reconciler.DirtyRegionQueue;
import org.eclipse.che.ide.api.editor.texteditor.TextEditor;
import org.eclipse.che.ide.api.event.ActivePartChangedEvent;
import org.eclipse.che.ide.api.event.ActivePartChangedHandler;
import org.eclipse.che.ide.api.event.EditorSettingsChangedEvent;
import org.eclipse.che.ide.api.event.EditorSettingsChangedEvent.EditorSettingsChangedHandler;
import org.eclipse.che.ide.api.parts.PartPresenter;
import org.eclipse.che.ide.api.resources.Project;
import org.eclipse.che.ide.api.resources.Resource;
import org.eclipse.che.ide.api.resources.VirtualFile;
import org.eclipse.che.ide.editor.preferences.EditorPreferencesManager;
import org.eclipse.che.ide.editor.synchronization.workingCopy.EditorWorkingCopySynchronizer;

import java.util.HashSet;

import static org.eclipse.che.ide.editor.orion.client.AutoSaveMode.Mode.ACTIVATED;
import static org.eclipse.che.ide.editor.orion.client.AutoSaveMode.Mode.DEACTIVATED;
import static org.eclipse.che.ide.editor.orion.client.AutoSaveMode.Mode.SUSPENDED;
import static org.eclipse.che.ide.editor.preferences.editorproperties.EditorProperties.ENABLE_AUTO_SAVE;

/**
 * Default implementation of {@link AutoSaveMode} which provides auto save function for editor content.
 *
 * @author Roman Nikitenko
 */
public class AutoSaveModeImpl implements AutoSaveMode, EditorSettingsChangedHandler, ActivePartChangedHandler, EditorOpenedEventHandler {
    private static final int DELAY = 1000;

    private EventBus                      eventBus;
    private EditorPreferencesManager      editorPreferencesManager;
    private EditorWorkingCopySynchronizer editorWorkingCopySynchronizer;
    private DocumentHandle                documentHandle;
    private TextEditor                    editor;
    private EditorPartPresenter           activeEditor;
    private DirtyRegionQueue              dirtyRegionQueue;
    private Mode                          mode;

    private HashSet<HandlerRegistration> handlerRegistrations = new HashSet<>(4);

    private final Timer saveTimer = new Timer() {
        @Override
        public void run() {
            save();
        }
    };

    @Inject
    public AutoSaveModeImpl(EventBus eventBus,
                            EditorPreferencesManager editorPreferencesManager,
                            EditorWorkingCopySynchronizer editorWorkingCopySynchronizer) {
        this.eventBus = eventBus;
        this.editorPreferencesManager = editorPreferencesManager;
        this.editorWorkingCopySynchronizer = editorWorkingCopySynchronizer;

        mode = ACTIVATED; //autosave is activated by default

        addHandlers();
    }

    @Override
    public DocumentHandle getDocumentHandle() {
        return documentHandle;
    }

    @Override
    public void setDocumentHandle(DocumentHandle documentHandle) {
        this.documentHandle = documentHandle;
    }

    @Override
    public void install(TextEditor editor) {
        this.editor = editor;
        this.dirtyRegionQueue = new DirtyRegionQueue();
        updateAutoSaveState();
    }

    @Override
    public void uninstall() {
        saveTimer.cancel();
        handlerRegistrations.forEach(HandlerRegistration::removeHandler);
    }

    @Override
    public void suspend() {
        mode = SUSPENDED;
    }

    @Override
    public void resume() {
        updateAutoSaveState();
    }

    @Override
    public boolean isActivated() {
        return mode == ACTIVATED;
    }

    @Override
    public void onEditorSettingsChanged(EditorSettingsChangedEvent event) {
        updateAutoSaveState();
    }

    private void updateAutoSaveState() {
        Boolean autoSaveValue = editorPreferencesManager.getBooleanValueFor(ENABLE_AUTO_SAVE);
        if (autoSaveValue == null) {
            return;
        }

        if (DEACTIVATED != mode && !autoSaveValue) {
            mode = DEACTIVATED;
            saveTimer.cancel();
        } else if (ACTIVATED != mode && autoSaveValue) {
            mode = ACTIVATED;

            saveTimer.cancel();
            saveTimer.schedule(DELAY);
        }
    }

    @Override
    public void onActivePartChanged(ActivePartChangedEvent event) {
        PartPresenter activePart = event.getActivePart();
        if (activePart instanceof EditorPartPresenter) {
            activeEditor = (EditorPartPresenter)activePart;
        }
    }

    @Override
    public void onEditorOpened(EditorOpenedEvent editorOpenedEvent) {
        if (documentHandle != null && editor == editorOpenedEvent.getEditor()) {
            HandlerRegistration documentChangeHandlerRegistration =
                    documentHandle.getDocEventBus().addHandler(DocumentChangeEvent.TYPE, this);
            handlerRegistrations.add(documentChangeHandlerRegistration);
        }
    }

    @Override
    public void onDocumentChange(final DocumentChangeEvent event) {
        if (documentHandle == null || !event.getDocument().isSameAs(documentHandle)) {
            return;
        }

        if (SUSPENDED == mode && editor != activeEditor) {
            return;
        }

        createDirtyRegion(event);

        saveTimer.cancel();
        saveTimer.schedule(DELAY);

    }

    /**
     * Creates a dirty region for a document event and adds it to the queue.
     *
     * @param event
     *         the document event for which to create a dirty region
     */
    private void createDirtyRegion(final DocumentChangeEvent event) {
        if (event.getRemoveCharCount() == 0 && event.getText() != null && !event.getText().isEmpty()) {
            // Insert
            dirtyRegionQueue.addDirtyRegion(new DirtyRegion(event.getOffset(),
                                                            event.getLength(),
                                                            DirtyRegion.INSERT,
                                                            event.getText()));

        } else if (event.getText() == null || event.getText().isEmpty()) {
            // Remove
            dirtyRegionQueue.addDirtyRegion(new DirtyRegion(event.getOffset(),
                                                            event.getRemoveCharCount(),
                                                            DirtyRegion.REMOVE,
                                                            null));

        } else {
            // Replace (Remove + Insert)
            dirtyRegionQueue.addDirtyRegion(new DirtyRegion(event.getOffset(),
                                                            event.getRemoveCharCount(),
                                                            DirtyRegion.REMOVE,
                                                            null));
            dirtyRegionQueue.addDirtyRegion(new DirtyRegion(event.getOffset(),
                                                            event.getLength(),
                                                            DirtyRegion.INSERT,
                                                            event.getText()));
        }
    }

    private void save() {
        if (ACTIVATED == mode && editor.isDirty()) {
            editor.doSave();
        }

        VirtualFile file = editor.getEditorInput().getFile();
        Project project = getProject(file);
        if (project == null) {
            return;
        }

        String filePath = file.getLocation().toString();
        String projectPath = project.getPath();

        while (dirtyRegionQueue.getSize() > 0) {
            editorWorkingCopySynchronizer.synchronize(filePath, projectPath, dirtyRegionQueue.removeNextDirtyRegion());
        }
    }

    private Project getProject(VirtualFile file) {
        if (file == null || !(file instanceof Resource)) {
            return null;
        }

        Project project = ((Resource)file).getProject();
        return (project != null && project.exists()) ? project : null;
    }

    private void addHandlers() {
        HandlerRegistration activePartChangedHandlerRegistration = eventBus.addHandler(ActivePartChangedEvent.TYPE, this);
        handlerRegistrations.add(activePartChangedHandlerRegistration);

        HandlerRegistration editorSettingsChangedHandlerRegistration = eventBus.addHandler(EditorSettingsChangedEvent.TYPE, this);
        handlerRegistrations.add(editorSettingsChangedHandlerRegistration);

        HandlerRegistration editorOpenedHandlerRegistration = eventBus.addHandler(EditorOpenedEvent.TYPE, this);
        handlerRegistrations.add(editorOpenedHandlerRegistration);
    }
}
