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

/**
 * 上传页控制器：负责上传相关 UI 与异步上传流程。
 */
public class UploadPanelController {

    /** 主窗口，用于弹窗和原生文件选择器挂靠。 */
    private final JFrame owner;
    /** 与服务端通信的客户端。 */
    private final DocumentServiceClient serviceClient;
    /** 本地文档加密和关键词构建服务。 */
    private final DocumentOperationService operationService;
    /** 当前用户的本地密钥集合。 */
    private final ClientKeyManager.KeyBundle keyBundle;
    /** 上传成功后刷新文档列表的回调。 */
    private final Runnable refreshDocumentsAction;

    /** 当前已选择准备批量上传的文件路径。 */
    private final List<Path> selectedFilePaths = new ArrayList<>();
    /** 全局忙碌状态管理器，主窗口装配完成后注入。 */
    private UiBusyStateManager busyStateManager;

    /** 自动生成的文档 ID 输入框，纯文本上传时使用。 */
    private JTextField docIdField;
    /** 用户输入的文档描述，会进入加密关键词元数据。 */
    private JTextField descriptionField;
    /** 纯文本上传内容输入区。 */
    private JTextArea plainTextContentArea;
    /** 展示当前文件选择状态。 */
    private JLabel selectedFileLabel;
    /** 导入文件或文件夹菜单按钮。 */
    private JButton importButton;
    /** 清空已选择文件按钮。 */
    private JButton clearFileButton;
    /** 触发上传按钮。 */
    private JButton uploadButton;
    /** 上传页状态文本。 */
    private JLabel uploadStatusLabel;
    /** 上传页进度条。 */
    private JProgressBar uploadProgressBar;

    /**
     * 创建上传页控制器。
     */
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

    /**
     * 创建上传页完整 UI。
     */
    public JPanel createPanel() {
        JPanel uploadPanel = new JPanel(new BorderLayout(10, 10));
        uploadPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 顶部表单包含文档 ID、文件选择状态和导入动作。
        JPanel uploadFormPanel = new JPanel(new GridLayout(3, 2, 5, 5));
        uploadFormPanel.add(new JLabel("Document ID:"));
        docIdField = new JTextField();
        docIdField.setEditable(false);
        docIdField.setText(DocumentIdGenerator.generate());
        uploadFormPanel.add(docIdField);

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

        // 中间区域同时支持输入描述和粘贴纯文本正文。
        descriptionField = new JTextField();

        plainTextContentArea = new JTextArea(14, 20);
        plainTextContentArea.setLineWrap(true);
        plainTextContentArea.setWrapStyleWord(true);

        JScrollPane contentScrollPane = new JScrollPane(plainTextContentArea);

        JPanel centerPanel = new JPanel(new BorderLayout(8, 8));
        JPanel descriptionPanel = new JPanel(new BorderLayout(6, 0));
        descriptionPanel.add(new JLabel("Description:"), BorderLayout.WEST);
        descriptionPanel.add(descriptionField, BorderLayout.CENTER);
        centerPanel.add(descriptionPanel, BorderLayout.NORTH);
        centerPanel.add(UiComponentFactory.createSectionPanel(
                "Content (plain text)",
                "Paste plain text here when uploading as a text document (without selecting files).",
                contentScrollPane
        ), BorderLayout.CENTER);
        uploadPanel.add(centerPanel, BorderLayout.CENTER);

        // 底部放置进度状态和上传按钮。
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
        registerEnterToUpload(uploadPanel);
        return uploadPanel;
    }

    /** 注入忙碌状态管理器。 */
    public void setBusyStateManager(UiBusyStateManager busyStateManager) {
        this.busyStateManager = busyStateManager;
    }

    /** 返回上传页状态标签，供忙碌状态管理器统一更新。 */
    public JLabel getStatusLabel() {
        return uploadStatusLabel;
    }

    /** 返回上传页进度条，供忙碌状态管理器统一显示/隐藏。 */
    public JProgressBar getProgressBar() {
        return uploadProgressBar;
    }

    /** 返回后台任务运行时需要禁用的上传页控件。 */
    public List<JComponent> getBusySensitiveComponents() {
        List<JComponent> components = new ArrayList<>();
        components.add(uploadButton);
        components.add(importButton);
        components.add(clearFileButton);
        components.add(descriptionField);
        components.add(plainTextContentArea);
        return components;
    }

    /**
     * 响应上传按钮：校验输入、锁定 UI，并启动后台上传任务。
     */
    private void handleUpload() {
        String description = descriptionField.getText();
        String plainTextContent = plainTextContentArea.getText();

        if ((plainTextContent == null || plainTextContent.isBlank()) && selectedFilePaths.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "Please enter plain text content or choose files/folder.", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }

        final List<Path> filesToUpload = new ArrayList<>(selectedFilePaths);
        final String textDocId = docIdField.getText().trim();
        busyStateManager.setUploadBusy(true, initialUploadStatus(filesToUpload));
        // SwingWorker 在后台线程执行加密和网络上传，done/process 回到 EDT 更新界面。
        new SwingWorker<UploadTaskResult, String>() {
            @Override
            protected UploadTaskResult doInBackground() {
                try {
                    return performUpload(textDocId, description, plainTextContent, filesToUpload, status -> publish(status));
                } catch (Exception ex) {
                    ex.printStackTrace();
                    return UploadTaskResult.error("Upload failed: " + DocumentOperationService.describeException(ex));
                }
            }

            @Override
            protected void process(List<String> chunks) {
                // publish 可能累计多条状态，只显示最新一条即可。
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
                // 先解除忙碌，再刷新列表或弹窗，避免用户觉得界面被卡住。
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

    /**
     * 根据当前选择执行纯文本上传或批量文件上传。
     */
    private UploadTaskResult performUpload(
            String textDocId,
            String description,
            String plainTextContent,
            List<Path> filesToUpload,
            Consumer<String> statusUpdater
    ) throws Exception {
        if (filesToUpload.isEmpty()) {
            // 未选择文件时，把文本框内容当作一个 text/plain 文档上传。
            statusUpdater.accept("Encrypting and uploading text content...");
            String docId = textDocId;
            DocumentOperationService.UploadContent uploadContent = operationService.resolveTextUploadContent(docId, plainTextContent);
            ServerResponse response = uploadSingleDocument(docId, uploadContent, description);
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
                // 每个文件独立构建内容、加密、上传；单个失败不会终止整个批次。
                DocumentOperationService.UploadContent uploadContent = operationService.resolveFileUploadContent(filePath);
                ServerResponse response = uploadSingleDocument(docId, uploadContent, description);
                if (response.isSuccess()) {
                    successCount++;
                } else {
                    failures.add(uploadContent.fileName() + ": " + response.getMessage());
                }
            } catch (Exception fileException) {
                failures.add(fileName + ": " + DocumentOperationService.describeException(fileException));
            }
        }

        // 批量上传结束后汇总成功数量和失败原因。
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

    /**
     * 构建单个加密文档并发送给服务端。
     */
    private ServerResponse uploadSingleDocument(String docId, DocumentOperationService.UploadContent uploadContent, String descriptionInput) throws Exception {
        EncryptedData data = operationService.buildEncryptedData(docId, uploadContent, descriptionInput, keyBundle.desKey(), keyBundle.peksPublicKey());
        return serviceClient.upload(data);
    }

    /**
     * 打开系统文件选择框，支持一次选择多个文件。
     */
    private void chooseFiles() {
        if (busyStateManager != null && busyStateManager.isBusy()) {
            return;
        }
        FileDialog fileDialog = new FileDialog(owner, "Select Files to Upload", FileDialog.LOAD);
        fileDialog.setMultipleMode(true);
        fileDialog.setVisible(true);

        // FileDialog 返回 File 数组，将其转换成 Path 供后续 NIO 读取。
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

    /**
     * 打开文件夹选择框，并收集文件夹下所有普通文件。
     */
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

    /**
     * 在导入按钮下方弹出文件/文件夹导入菜单。
     */
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

        applyPopupMenuScale(menu, invoker, filesItem, folderItem);
        menu.show(invoker, 0, invoker.getHeight());
    }

    /**
     * 让弹出菜单字体和按钮当前缩放比例一致。
     */
    private void applyPopupMenuScale(JPopupMenu menu, Component invoker, JMenuItem... items) {
        Font targetFont = invoker.getFont();
        if (targetFont == null) {
            return;
        }

        int horizontalPadding = Math.max(10, Math.round(targetFont.getSize2D() * 0.8f));
        int verticalPadding = Math.max(4, Math.round(targetFont.getSize2D() * 0.35f));
        int minHeight = Math.max(invoker.getHeight(), 24);

        menu.setFont(targetFont);
        for (JMenuItem item : items) {
            item.setFont(targetFont);
            item.setBorder(BorderFactory.createEmptyBorder(verticalPadding, horizontalPadding, verticalPadding, horizontalPadding));
            Dimension preferredSize = item.getPreferredSize();
            item.setPreferredSize(new Dimension(preferredSize.width, Math.max(preferredSize.height, minHeight)));
        }
    }

    /** 清空当前文件选择。 */
    private void clearSelectedFiles() {
        selectedFilePaths.clear();
        updateSelectedFilesLabel();
    }

    /** 用新的文件路径集合替换当前选择。 */
    private void replaceSelectedFiles(List<Path> paths) {
        selectedFilePaths.clear();
        selectedFilePaths.addAll(paths);
        updateSelectedFilesLabel();
    }

    /** 根据已选择文件数量刷新界面提示。 */
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
        selectedFileLabel.setText(selectedFilePaths.size() + " files selected");
    }

    /** 生成上传刚开始时的状态文本。 */
    private String initialUploadStatus(List<Path> filesToUpload) {
        return filesToUpload.isEmpty() ? "Uploading text content..." : "Preparing " + filesToUpload.size() + " file(s)...";
    }

    /** 上传成功后重置表单并生成新的文档 ID。 */
    private void resetUploadForm() {
        docIdField.setText(DocumentIdGenerator.generate());
        descriptionField.setText("");
        plainTextContentArea.setText("");
        clearSelectedFiles();
    }

    /**
     * 给上传面板绑定回车快捷上传；焦点在多行文本框内时保留换行行为。
     */
    private void registerEnterToUpload(JComponent root) {
        InputMap inputMap = root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actionMap = root.getActionMap();
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "triggerUpload");
        actionMap.put("triggerUpload", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focusOwner instanceof JTextArea) {
                    return;
                }
                handleUpload();
            }
        });
    }

    /**
     * 上传后台任务的结果对象。
     *
     * @param title 弹窗标题。
     * @param message 弹窗内容。
     * @param messageType JOptionPane 消息类型。
     * @param clearInputs 是否清空上传表单。
     * @param refreshDocuments 是否刷新文档列表。
     */
    private record UploadTaskResult(
            String title,
            String message,
            int messageType,
            boolean clearInputs,
            boolean refreshDocuments
    ) {
        /** 创建上传失败结果。 */
        private static UploadTaskResult error(String message) {
            return new UploadTaskResult("Upload Result", message, JOptionPane.ERROR_MESSAGE, false, false);
        }
    }
}
