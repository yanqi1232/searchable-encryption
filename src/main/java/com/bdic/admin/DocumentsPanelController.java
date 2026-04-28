package com.bdic.admin;

import com.bdic.crypto.ClientKeyManager;
import com.bdic.crypto.DESUtil;
import com.bdic.model.DocumentSummary;
import com.bdic.model.EncryptedData;
import com.bdic.model.ServerResponse;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 文档页控制器：负责文档列表、下载、删除、重建索引及其批量操作。
 */
public class DocumentsPanelController {

    private final JFrame owner;
    private final DocumentServiceClient serviceClient;
    private final DocumentOperationService operationService;
    private final ClientKeyManager.KeyBundle keyBundle;
    private UiBusyStateManager busyStateManager;

    private JTextField documentActionField;
    private JTable documentTable;
    private DefaultTableModel documentTableModel;
    private JButton refreshButton;
    private JButton downloadButton;
    private JButton deleteButton;
    private JButton rebuildIndexButton;
    private JLabel documentsStatusLabel;
    private JProgressBar documentsProgressBar;

    private final List<DocumentSummary> currentDocumentSummaries = new ArrayList<>();

    public DocumentsPanelController(
            JFrame owner,
            DocumentServiceClient serviceClient,
            DocumentOperationService operationService,
            ClientKeyManager.KeyBundle keyBundle
    ) {
        this.owner = owner;
        this.serviceClient = serviceClient;
        this.operationService = operationService;
        this.keyBundle = keyBundle;
    }

    public JPanel createPanel() {
        JPanel documentsPanel = new JPanel(new BorderLayout(10, 10));
        documentsPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel topActionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        refreshButton = new JButton("Refresh List");
        refreshButton.addActionListener(e -> refreshDocuments());
        UiComponentFactory.styleSecondaryButton(refreshButton);
        topActionsPanel.add(refreshButton);

        topActionsPanel.add(new JLabel("Doc ID:"));
        documentActionField = new JTextField(18);
        topActionsPanel.add(documentActionField);

        downloadButton = new JButton("Download");
        downloadButton.addActionListener(e -> downloadDocument());
        UiComponentFactory.stylePrimaryButton(downloadButton);
        topActionsPanel.add(downloadButton);

        deleteButton = new JButton("Delete");
        deleteButton.addActionListener(e -> deleteDocument());
        UiComponentFactory.styleDangerButton(deleteButton);
        topActionsPanel.add(deleteButton);

        rebuildIndexButton = new JButton("Rebuild Index");
        rebuildIndexButton.addActionListener(e -> rebuildDocumentIndex());
        UiComponentFactory.styleSecondaryButton(rebuildIndexButton);
        topActionsPanel.add(rebuildIndexButton);

        documentsPanel.add(UiComponentFactory.createSectionPanel("Document Actions", "Select one or more rows below or enter a Document ID to manage encrypted files.", topActionsPanel), BorderLayout.NORTH);

        documentTableModel = new DefaultTableModel(new Object[]{"Document ID", "File Name", "Type", "Size", "Keywords", "Created At"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        documentTable = new JTable(documentTableModel);
        documentTable.setRowHeight(28);
        documentTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        documentTable.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) {
                return;
            }
            List<DocumentSummary> selectedDocuments = getSelectedDocumentSummaries();
            if (selectedDocuments.size() == 1) {
                documentActionField.setText(selectedDocuments.get(0).getDocId());
            } else if (selectedDocuments.size() > 1) {
                documentActionField.setText(selectedDocuments.size() + " selected");
            }
        });

        JScrollPane tableScrollPane = new JScrollPane(documentTable);
        documentsPanel.add(UiComponentFactory.createSectionPanel("My Documents", "Browse uploaded documents and use the actions above to manage one or multiple files.", tableScrollPane), BorderLayout.CENTER);

        documentsStatusLabel = new JLabel(" ");
        documentsStatusLabel.setForeground(new Color(75, 85, 99));
        documentsProgressBar = new JProgressBar();
        documentsProgressBar.setIndeterminate(true);
        documentsProgressBar.setVisible(false);
        documentsProgressBar.setPreferredSize(new Dimension(180, 18));

        JPanel documentsStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        documentsStatusPanel.add(documentsProgressBar);
        documentsStatusPanel.add(Box.createHorizontalStrut(8));
        documentsStatusPanel.add(documentsStatusLabel);
        documentsPanel.add(documentsStatusPanel, BorderLayout.SOUTH);
        return documentsPanel;
    }

    public void setBusyStateManager(UiBusyStateManager busyStateManager) {
        this.busyStateManager = busyStateManager;
    }

    public JLabel getStatusLabel() {
        return documentsStatusLabel;
    }

    public JProgressBar getProgressBar() {
        return documentsProgressBar;
    }

    public List<JComponent> getBusySensitiveComponents() {
        List<JComponent> components = new ArrayList<>();
        components.add(refreshButton);
        components.add(downloadButton);
        components.add(deleteButton);
        components.add(rebuildIndexButton);
        components.add(documentActionField);
        components.add(documentTable);
        return components;
    }

    public void refreshDocuments() {
        if (busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }
        busyStateManager.setDocumentsBusy(true, "Refreshing document list...");
        new SwingWorker<DocumentListTaskResult, Void>() {
            @Override
            protected DocumentListTaskResult doInBackground() {
                try {
                    return DocumentListTaskResult.success(loadDocumentSummaries());
                } catch (Exception e) {
                    return DocumentListTaskResult.failure("Failed to refresh document list: " + DocumentOperationService.describeException(e));
                }
            }

            @Override
            protected void done() {
                DocumentListTaskResult result;
                try {
                    result = get();
                } catch (Exception e) {
                    result = DocumentListTaskResult.failure("Failed to refresh document list: " + DocumentOperationService.describeException(e));
                }

                if (busyStateManager != null) {
                    busyStateManager.setDocumentsBusy(false, " ");
                }
                if (result.errorMessage() != null) {
                    clearDocumentTable();
                    JOptionPane.showMessageDialog(owner, result.errorMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                applyDocumentSummaries(result.documents());
            }
        }.execute();
    }

    private List<DocumentSummary> loadDocumentSummaries() throws Exception {
        ServerResponse response = serviceClient.listDocuments();
        if (!response.isSuccess()) {
            throw new IllegalStateException(response.getMessage());
        }
        if (!(response.getData() instanceof List<?> rawDocuments)) {
            throw new IllegalStateException("Unexpected response from server.");
        }

        List<DocumentSummary> summaries = new ArrayList<>();
        for (Object rawDocument : rawDocuments) {
            summaries.add((DocumentSummary) rawDocument);
        }
        return summaries;
    }

    private void applyDocumentSummaries(List<DocumentSummary> summaries) {
        clearDocumentTable();
        for (DocumentSummary summary : summaries) {
            currentDocumentSummaries.add(summary);
            documentTableModel.addRow(new Object[]{
                    summary.getDocId(),
                    summary.getFileName(),
                    summary.getMediaType(),
                    formatFileSize(summary.getFileSize()),
                    summary.getKeywordCount(),
                    summary.getCreatedAt()
            });
        }
    }

    private void downloadDocument() {
        if (busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }
        try {
            final List<String> targetDocIds = resolveTargetDocumentIds();
            if (targetDocIds.isEmpty()) {
                JOptionPane.showMessageDialog(owner, "Please enter a document ID.", "Warning", JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (targetDocIds.size() > 1) {
                String selectedFolder = NativeDialogHelper.chooseFolder("Select Folder to Save Downloaded Files");
                if (selectedFolder == null || selectedFolder.isBlank()) {
                    return;
                }
                final Path targetDirectory = Path.of(selectedFolder);
                busyStateManager.setDocumentsBusy(true, "Downloading " + targetDocIds.size() + " documents...");
                new SwingWorker<DocumentOperationTaskResult, String>() {
                    @Override
                    protected DocumentOperationTaskResult doInBackground() {
                        return performBatchDownload(targetDocIds, targetDirectory, status -> publish(status));
                    }

                    @Override
                    protected void process(List<String> chunks) {
                        if (!chunks.isEmpty() && busyStateManager != null) {
                            busyStateManager.updateDocumentsStatus(chunks.get(chunks.size() - 1));
                        }
                    }

                    @Override
                    protected void done() {
                        finishDocumentOperation(this, "Download failed.");
                    }
                }.execute();
                return;
            }

            final String docId = targetDocIds.get(0);
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setSelectedFile(new File(resolveSuggestedFileName(docId)));
            int option = fileChooser.showSaveDialog(owner);
            if (option != JFileChooser.APPROVE_OPTION) {
                return;
            }

            final Path targetPath = fileChooser.getSelectedFile().toPath();
            busyStateManager.setDocumentsBusy(true, "Downloading " + docId + "...");
            new SwingWorker<DocumentOperationTaskResult, String>() {
                @Override
                protected DocumentOperationTaskResult doInBackground() {
                    publish("Downloading " + docId + "...");
                    return performSingleDownload(docId, targetPath);
                }

                @Override
                protected void process(List<String> chunks) {
                    if (!chunks.isEmpty() && busyStateManager != null) {
                        busyStateManager.updateDocumentsStatus(chunks.get(chunks.size() - 1));
                    }
                }

                @Override
                protected void done() {
                    finishDocumentOperation(this, "Download failed.");
                }
            }.execute();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(owner, "Download failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteDocument() {
        if (busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }
        try {
            final List<String> targetDocIds = resolveTargetDocumentIds();
            if (targetDocIds.isEmpty()) {
                JOptionPane.showMessageDialog(owner, "Please enter a document ID.", "Warning", JOptionPane.WARNING_MESSAGE);
                return;
            }

            int confirmed = JOptionPane.showConfirmDialog(
                    owner,
                    targetDocIds.size() == 1
                            ? "Delete document " + targetDocIds.get(0) + "?"
                            : "Delete " + targetDocIds.size() + " selected documents?",
                    targetDocIds.size() == 1 ? "Delete Document" : "Batch Delete",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (confirmed != JOptionPane.OK_OPTION) {
                return;
            }

            busyStateManager.setDocumentsBusy(true, targetDocIds.size() == 1
                    ? "Deleting " + targetDocIds.get(0) + "..."
                    : "Deleting " + targetDocIds.size() + " documents...");
            new SwingWorker<DocumentOperationTaskResult, String>() {
                @Override
                protected DocumentOperationTaskResult doInBackground() {
                    return performDelete(targetDocIds, status -> publish(status));
                }

                @Override
                protected void process(List<String> chunks) {
                    if (!chunks.isEmpty() && busyStateManager != null) {
                        busyStateManager.updateDocumentsStatus(chunks.get(chunks.size() - 1));
                    }
                }

                @Override
                protected void done() {
                    finishDocumentOperation(this, "Delete failed.");
                }
            }.execute();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(owner, "Delete failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void rebuildDocumentIndex() {
        if (busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }
        try {
            final List<String> targetDocIds = resolveTargetDocumentIds();
            if (targetDocIds.isEmpty()) {
                JOptionPane.showMessageDialog(owner, "Please enter a document ID.", "Warning", JOptionPane.WARNING_MESSAGE);
                return;
            }

            int confirmed = JOptionPane.showConfirmDialog(
                    owner,
                    targetDocIds.size() == 1
                            ? "Rebuild index for document " + targetDocIds.get(0) + "?"
                            : "Rebuild index for " + targetDocIds.size() + " selected documents?",
                    targetDocIds.size() == 1 ? "Rebuild Index" : "Batch Rebuild Index",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );
            if (confirmed != JOptionPane.OK_OPTION) {
                return;
            }

            busyStateManager.setDocumentsBusy(true, targetDocIds.size() == 1
                    ? "Rebuilding index for " + targetDocIds.get(0) + "..."
                    : "Rebuilding indexes for " + targetDocIds.size() + " documents...");
            new SwingWorker<DocumentOperationTaskResult, String>() {
                @Override
                protected DocumentOperationTaskResult doInBackground() {
                    return performRebuildIndex(targetDocIds, status -> publish(status));
                }

                @Override
                protected void process(List<String> chunks) {
                    if (!chunks.isEmpty() && busyStateManager != null) {
                        busyStateManager.updateDocumentsStatus(chunks.get(chunks.size() - 1));
                    }
                }

                @Override
                protected void done() {
                    finishDocumentOperation(this, "Rebuild index failed.");
                }
            }.execute();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(owner, "Rebuild index failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private EncryptedData downloadDocumentById(String docId) throws Exception {
        ServerResponse response = serviceClient.downloadDocument(docId);
        if (!response.isSuccess()) {
            throw new IllegalStateException(response.getMessage());
        }
        return (EncryptedData) response.getData();
    }

    private ServerResponse deleteDocumentById(String docId) throws Exception {
        return serviceClient.deleteDocument(docId);
    }

    private ServerResponse rebuildDocumentIndexById(String docId) throws Exception {
        EncryptedData data = downloadDocumentById(docId);
        EncryptedData rebuilt = operationService.rebuildIndex(data, keyBundle.desKey(), keyBundle.peksKey(), owner);
        return serviceClient.upload(rebuilt);
    }

    private DocumentOperationTaskResult performSingleDownload(String docId, Path targetPath) {
        try {
            EncryptedData data = downloadDocumentById(docId);
            byte[] decryptedContent = DESUtil.decrypt(data.getEncryptedContent(), keyBundle.desKey());
            Files.write(targetPath, decryptedContent);
            return new DocumentOperationTaskResult(
                    "Download Result",
                    "File saved to: " + targetPath,
                    JOptionPane.INFORMATION_MESSAGE,
                    false
            );
        } catch (Exception exception) {
            return DocumentOperationTaskResult.error("Download failed: " + DocumentOperationService.describeException(exception));
        }
    }

    private DocumentOperationTaskResult performBatchDownload(List<String> docIds, Path targetDirectory, Consumer<String> statusUpdater) {
        int successCount = 0;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < docIds.size(); i++) {
            String docId = docIds.get(i);
            statusUpdater.accept("Downloading (" + (i + 1) + "/" + docIds.size() + "): " + docId);
            try {
                EncryptedData data = downloadDocumentById(docId);
                byte[] decryptedContent = DESUtil.decrypt(data.getEncryptedContent(), keyBundle.desKey());
                Path targetPath = operationService.resolveUniqueChildPath(targetDirectory, operationService.defaultFileName(data));
                Files.write(targetPath, decryptedContent);
                successCount++;
            } catch (Exception exception) {
                failures.add(docId + ": " + DocumentOperationService.describeException(exception));
            }
        }
        return buildBatchOperationResult("Batch Download Result", "Download", successCount, failures, false);
    }

    private DocumentOperationTaskResult performDelete(List<String> docIds, Consumer<String> statusUpdater) {
        int successCount = 0;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < docIds.size(); i++) {
            String docId = docIds.get(i);
            statusUpdater.accept("Deleting (" + (i + 1) + "/" + docIds.size() + "): " + docId);
            try {
                ServerResponse response = deleteDocumentById(docId);
                if (response.isSuccess()) {
                    successCount++;
                } else {
                    failures.add(docId + ": " + response.getMessage());
                }
            } catch (Exception exception) {
                failures.add(docId + ": " + DocumentOperationService.describeException(exception));
            }
        }
        return buildBatchOperationResult(
                docIds.size() == 1 ? "Delete Result" : "Batch Delete Result",
                "Delete",
                successCount,
                failures,
                successCount > 0
        );
    }

    private DocumentOperationTaskResult performRebuildIndex(List<String> docIds, Consumer<String> statusUpdater) {
        int successCount = 0;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < docIds.size(); i++) {
            String docId = docIds.get(i);
            statusUpdater.accept("Rebuilding (" + (i + 1) + "/" + docIds.size() + "): " + docId);
            try {
                ServerResponse response = rebuildDocumentIndexById(docId);
                if (response.isSuccess()) {
                    successCount++;
                } else {
                    failures.add(docId + ": " + response.getMessage());
                }
            } catch (Exception exception) {
                failures.add(docId + ": " + DocumentOperationService.describeException(exception));
            }
        }
        return buildBatchOperationResult(
                docIds.size() == 1 ? "Rebuild Index" : "Batch Rebuild Result",
                "Rebuild",
                successCount,
                failures,
                successCount > 0
        );
    }

    private DocumentOperationTaskResult buildBatchOperationResult(
            String title,
            String actionLabel,
            int successCount,
            List<String> failures,
            boolean refreshDocuments
    ) {
        StringBuilder summary = new StringBuilder();
        summary.append(actionLabel).append(" finished.\n")
                .append("Success: ").append(successCount).append('\n')
                .append("Failed: ").append(failures.size());
        if (!failures.isEmpty()) {
            summary.append("\n\nFailures:\n").append(String.join("\n", failures));
        }
        return new DocumentOperationTaskResult(
                title,
                summary.toString(),
                failures.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE,
                refreshDocuments
        );
    }

    private void finishDocumentOperation(SwingWorker<DocumentOperationTaskResult, String> worker, String fallbackError) {
        DocumentOperationTaskResult result;
        try {
            result = worker.get();
        } catch (Exception exception) {
            result = DocumentOperationTaskResult.error(fallbackError + " " + DocumentOperationService.describeException(exception));
        }

        if (busyStateManager != null) {
            busyStateManager.setDocumentsBusy(false, " ");
        }
        if (result.refreshDocuments()) {
            refreshDocuments();
        }
        JOptionPane.showMessageDialog(
                owner,
                result.message(),
                result.title(),
                result.messageType()
        );
    }

    private List<DocumentSummary> getSelectedDocumentSummaries() {
        List<DocumentSummary> selected = new ArrayList<>();
        if (documentTable == null) {
            return selected;
        }
        for (int selectedRow : documentTable.getSelectedRows()) {
            if (selectedRow >= 0 && selectedRow < currentDocumentSummaries.size()) {
                selected.add(currentDocumentSummaries.get(selectedRow));
            }
        }
        return selected;
    }

    private List<String> resolveTargetDocumentIds() {
        List<String> selectedDocIds = getSelectedDocumentSummaries().stream()
                .map(DocumentSummary::getDocId)
                .distinct()
                .collect(Collectors.toList());
        if (!selectedDocIds.isEmpty()) {
            return selectedDocIds;
        }

        String docId = documentActionField.getText().trim();
        if (docId.isEmpty() || docId.endsWith(" selected")) {
            return List.of();
        }
        return List.of(docId);
    }

    private String resolveSuggestedFileName(String docId) {
        return currentDocumentSummaries.stream()
                .filter(summary -> docId.equals(summary.getDocId()))
                .map(DocumentSummary::getFileName)
                .filter(fileName -> fileName != null && !fileName.isBlank())
                .findFirst()
                .orElse(docId);
    }

    private void clearDocumentTable() {
        currentDocumentSummaries.clear();
        if (documentTableModel != null) {
            documentTableModel.setRowCount(0);
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private record DocumentListTaskResult(List<DocumentSummary> documents, String errorMessage) {
        private static DocumentListTaskResult success(List<DocumentSummary> documents) {
            return new DocumentListTaskResult(documents, null);
        }

        private static DocumentListTaskResult failure(String errorMessage) {
            return new DocumentListTaskResult(List.of(), errorMessage);
        }
    }

    private record DocumentOperationTaskResult(
            String title,
            String message,
            int messageType,
            boolean refreshDocuments
    ) {
        private static DocumentOperationTaskResult error(String message) {
            return new DocumentOperationTaskResult("Document Operation", message, JOptionPane.ERROR_MESSAGE, false);
        }
    }
}
