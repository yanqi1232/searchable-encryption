package com.bdic.admin;

import com.bdic.crypto.ClientKeyManager;
import com.bdic.model.DocumentIdGenerator;
import com.bdic.model.EncryptedData;
import com.bdic.model.ServerResponse;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 上传页控制器：负责上传相关 UI 与异步上传流程。
 */
public class UploadPanelController {

    private final JFrame owner;
    private final DocumentServiceClient serviceClient;
    private final DocumentOperationService operationService;
    private final ClientKeyManager.KeyBundle keyBundle;
    private final Runnable refreshDocumentsAction;

    private final List<Path> selectedFilePaths = new ArrayList<>();
    private UiBusyStateManager busyStateManager;

    private JTextField docIdField;
    private JTextArea contentArea;
    private JTextField keywordsField;
    private JLabel selectedFileLabel;
    private JButton importButton;
    private JButton clearFileButton;
    private JButton uploadButton;
    private JLabel uploadStatusLabel;
    private JProgressBar uploadProgressBar;

    public UploadPanelController(
            JFrame owner,
            DocumentServiceClient serviceClient,
            DocumentOperationService operationService,
            ClientKeyManager.KeyBundle keyBundle,
            Runnable refreshDocumentsAction
    ) {
        this.owner = owner;
        this.serviceClient = serviceClient;
        this.operationService = operationService;
        this.keyBundle = keyBundle;
        this.refreshDocumentsAction = refreshDocumentsAction;
    }

    public JPanel createPanel() {
        JPanel uploadPanel = new JPanel(new BorderLayout(10, 10));
        uploadPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel uploadFormPanel = new JPanel(new GridLayout(4, 2, 5, 5));
        uploadFormPanel.add(new JLabel("Document ID (auto):"));
        docIdField = new JTextField();
        docIdField.setEditable(false);
        docIdField.setText(DocumentIdGenerator.generate());
        uploadFormPanel.add(docIdField);

        uploadFormPanel.add(new JLabel("Keywords (optional):"));
        keywordsField = new JTextField();
        uploadFormPanel.add(keywordsField);

        uploadFormPanel.add(new JLabel("Selected Files:"));
        selectedFileLabel = new JLabel("No file or folder selected");
        uploadFormPanel.add(selectedFileLabel);

        uploadFormPanel.add(new JLabel("Import Actions:"));
        JPanel fileActionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        importButton = new JButton("Import");
        importButton.addActionListener(e -> showImportMenu(importButton));
        UiComponentFactory.stylePrimaryButton(importButton);
        clearFileButton = new JButton("Clear Selection");
        clearFileButton.addActionListener(e -> clearSelectedFiles());
        UiComponentFactory.styleSecondaryButton(clearFileButton);
        fileActionPanel.add(importButton);
        fileActionPanel.add(Box.createHorizontalStrut(8));
        fileActionPanel.add(clearFileButton);
        uploadFormPanel.add(fileActionPanel);

        uploadPanel.add(uploadFormPanel, BorderLayout.NORTH);

        contentArea = new JTextArea();
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(true);
        uploadPanel.add(UiComponentFactory.createSectionPanel("Document Content", "Enter text or choose files to encrypt and upload.", new JScrollPane(contentArea)), BorderLayout.CENTER);

        uploadButton = new JButton("Upload Document");
        uploadButton.addActionListener(e -> handleUpload());
        UiComponentFactory.stylePrimaryButton(uploadButton);
        uploadStatusLabel = new JLabel(" ");
        uploadStatusLabel.setForeground(new Color(75, 85, 99));
        uploadProgressBar = new JProgressBar();
        uploadProgressBar.setIndeterminate(true);
        uploadProgressBar.setVisible(false);
        uploadProgressBar.setPreferredSize(new Dimension(160, 18));

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        statusPanel.add(uploadProgressBar);
        statusPanel.add(Box.createHorizontalStrut(8));
        statusPanel.add(uploadStatusLabel);

        JPanel footerPanel = new JPanel(new BorderLayout());
        footerPanel.add(statusPanel, BorderLayout.WEST);
        footerPanel.add(uploadButton, BorderLayout.EAST);
        uploadPanel.add(footerPanel, BorderLayout.SOUTH);
        return uploadPanel;
    }

    public void setBusyStateManager(UiBusyStateManager busyStateManager) {
        this.busyStateManager = busyStateManager;
    }

    public JLabel getStatusLabel() {
        return uploadStatusLabel;
    }

    public JProgressBar getProgressBar() {
        return uploadProgressBar;
    }

    public List<JComponent> getBusySensitiveComponents() {
        List<JComponent> components = new ArrayList<>();
        components.add(uploadButton);
        components.add(importButton);
        components.add(clearFileButton);
        components.add(contentArea);
        components.add(keywordsField);
        return components;
    }

    private void handleUpload() {
        String content = contentArea.getText();
        String keywordsInput = keywordsField.getText().trim();

        if ((content == null || content.isBlank()) && selectedFilePaths.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "Please enter text content or choose files/folder.", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }

        final List<Path> filesToUpload = new ArrayList<>(selectedFilePaths);
        busyStateManager.setUploadBusy(true, initialUploadStatus(filesToUpload));
        new SwingWorker<UploadTaskResult, String>() {
            @Override
            protected UploadTaskResult doInBackground() {
                try {
                    return performUpload(content, keywordsInput, filesToUpload, status -> publish(status));
                } catch (Exception ex) {
                    ex.printStackTrace();
                    return UploadTaskResult.error("Upload failed: " + DocumentOperationService.describeException(ex));
                }
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty() && busyStateManager != null) {
                    busyStateManager.updateUploadStatus(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                UploadTaskResult result;
                try {
                    result = get();
                } catch (Exception ex) {
                    ex.printStackTrace();
                    result = UploadTaskResult.error("Upload failed: " + DocumentOperationService.describeException(ex));
                }

                if (result.clearInputs()) {
                    resetUploadForm();
                }
                if (busyStateManager != null) {
                    busyStateManager.setUploadBusy(false, " ");
                }
                if (result.refreshDocuments()) {
                    refreshDocumentsAction.run();
                }
                JOptionPane.showMessageDialog(
                        owner,
                        result.message(),
                        result.title(),
                        result.messageType()
                );
            }
        }.execute();
    }

    private UploadTaskResult performUpload(
            String content,
            String keywordsInput,
            List<Path> filesToUpload,
            Consumer<String> statusUpdater
    ) throws Exception {
        if (filesToUpload.isEmpty()) {
            statusUpdater.accept("Encrypting and uploading text document...");
            String docId = docIdField.getText().trim();
            DocumentOperationService.UploadContent uploadContent = operationService.resolveTextUploadContent(docId, content);
            ServerResponse response = uploadSingleDocument(docId, uploadContent, keywordsInput);
            return new UploadTaskResult(
                    "Upload Result",
                    response.getMessage(),
                    response.isSuccess() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE,
                    response.isSuccess(),
                    response.isSuccess()
            );
        }

        int successCount = 0;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < filesToUpload.size(); i++) {
            Path filePath = filesToUpload.get(i);
            String fileName = filePath.getFileName() == null ? filePath.toString() : filePath.getFileName().toString();
            statusUpdater.accept("Uploading (" + (i + 1) + "/" + filesToUpload.size() + "): " + fileName);
            String docId = DocumentIdGenerator.generate();
            try {
                DocumentOperationService.UploadContent uploadContent = operationService.resolveFileUploadContent(filePath);
                ServerResponse response = uploadSingleDocument(docId, uploadContent, keywordsInput);
                if (response.isSuccess()) {
                    successCount++;
                } else {
                    failures.add(uploadContent.fileName() + ": " + response.getMessage());
                }
            } catch (Exception fileException) {
                failures.add(fileName + ": " + DocumentOperationService.describeException(fileException));
            }
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Batch upload finished.\n")
                .append("Success: ").append(successCount).append('\n')
                .append("Failed: ").append(failures.size());
        if (!failures.isEmpty()) {
            summary.append("\n\nFailures:\n").append(String.join("\n", failures));
        }

        return new UploadTaskResult(
                "Batch Upload Result",
                summary.toString(),
                failures.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE,
                true,
                successCount > 0
        );
    }

    private ServerResponse uploadSingleDocument(String docId, DocumentOperationService.UploadContent uploadContent, String keywordsInput) throws Exception {
        EncryptedData data = operationService.buildEncryptedData(docId, uploadContent, keywordsInput, keyBundle.desKey(), keyBundle.peksKey());
        return serviceClient.upload(data);
    }

    private void chooseFiles() {
        if (busyStateManager != null && busyStateManager.isBusy()) {
            return;
        }
        FileDialog fileDialog = new FileDialog(owner, "Select Files to Upload", FileDialog.LOAD);
        fileDialog.setMultipleMode(true);
        fileDialog.setVisible(true);

        File[] selectedFiles = fileDialog.getFiles();
        if (selectedFiles == null || selectedFiles.length == 0) {
            return;
        }

        List<Path> chosenPaths = new ArrayList<>();
        for (File selectedFile : selectedFiles) {
            if (selectedFile != null) {
                chosenPaths.add(selectedFile.toPath());
            }
        }
        replaceSelectedFiles(chosenPaths);
    }

    private void chooseFolder() {
        if (busyStateManager != null && busyStateManager.isBusy()) {
            return;
        }
        String selectedFolder = NativeDialogHelper.chooseFolder("Select Folder to Upload");
        if (selectedFolder == null || selectedFolder.isBlank()) {
            return;
        }

        try {
            List<Path> files = operationService.collectFiles(Path.of(selectedFolder));
            replaceSelectedFiles(files);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(owner, "Failed to load folder: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showImportMenu(Component invoker) {
        if (busyStateManager != null && busyStateManager.isBusy()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();

        JMenuItem filesItem = new JMenuItem("Import Files");
        filesItem.addActionListener(e -> chooseFiles());
        menu.add(filesItem);

        JMenuItem folderItem = new JMenuItem("Import Folder");
        folderItem.addActionListener(e -> chooseFolder());
        menu.add(folderItem);

        menu.show(invoker, 0, invoker.getHeight());
    }

    private void clearSelectedFiles() {
        selectedFilePaths.clear();
        updateSelectedFilesLabel();
    }

    private void replaceSelectedFiles(List<Path> paths) {
        selectedFilePaths.clear();
        selectedFilePaths.addAll(paths);
        updateSelectedFilesLabel();
    }

    private void updateSelectedFilesLabel() {
        if (selectedFileLabel == null) {
            return;
        }
        if (selectedFilePaths.isEmpty()) {
            selectedFileLabel.setText("No file or folder selected");
            return;
        }
        if (selectedFilePaths.size() == 1) {
            selectedFileLabel.setText(selectedFilePaths.get(0).getFileName().toString());
            return;
        }
        String preview = selectedFilePaths.stream()
                .limit(3)
                .map(path -> path.getFileName().toString())
                .collect(Collectors.joining(", "));
        if (selectedFilePaths.size() > 3) {
            preview += " ... (" + selectedFilePaths.size() + " files)";
        }
        selectedFileLabel.setText(preview);
    }

    private String initialUploadStatus(List<Path> filesToUpload) {
        return filesToUpload.isEmpty() ? "Uploading text document..." : "Preparing " + filesToUpload.size() + " file(s)...";
    }

    private void resetUploadForm() {
        docIdField.setText(DocumentIdGenerator.generate());
        contentArea.setText("");
        keywordsField.setText("");
        clearSelectedFiles();
    }

    private record UploadTaskResult(
            String title,
            String message,
            int messageType,
            boolean clearInputs,
            boolean refreshDocuments
    ) {
        private static UploadTaskResult error(String message) {
            return new UploadTaskResult("Upload Result", message, JOptionPane.ERROR_MESSAGE, false, false);
        }
    }
}
