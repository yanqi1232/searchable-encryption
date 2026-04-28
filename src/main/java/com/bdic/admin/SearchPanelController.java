package com.bdic.admin;

import com.bdic.crypto.ClientKeyManager;
import com.bdic.crypto.DESUtil;
import com.bdic.crypto.PEKSUtil;
import com.bdic.model.EncryptedData;
import com.bdic.model.ServerResponse;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 搜索页控制器：负责关键词搜索 UI 与异步搜索流程。
 */
public class SearchPanelController {

    private final JFrame owner;
    private final DocumentServiceClient serviceClient;
    private final DocumentOperationService operationService;
    private final ClientKeyManager.KeyBundle keyBundle;
    private UiBusyStateManager busyStateManager;

    private JTextField searchField;
    private JTextArea resultArea;
    private JButton searchButton;
    private JLabel searchStatusLabel;
    private JProgressBar searchProgressBar;

    public SearchPanelController(
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
        JPanel searchPanel = new JPanel(new BorderLayout(10, 10));
        searchPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel searchFormPanel = new JPanel(new BorderLayout(8, 8));
        searchFormPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        searchFormPanel.add(new JLabel("Search Keyword"), BorderLayout.WEST);
        searchField = new JTextField();
        searchField.addActionListener(e -> handleSearch());
        searchFormPanel.add(searchField, BorderLayout.CENTER);

        searchButton = new JButton("Search");
        searchButton.addActionListener(e -> handleSearch());
        UiComponentFactory.stylePrimaryButton(searchButton);
        searchFormPanel.add(searchButton, BorderLayout.EAST);
        searchPanel.add(UiComponentFactory.createSectionPanel("Keyword Search", "Search by keyword prefix, file name, or extracted document text.", searchFormPanel), BorderLayout.NORTH);

        resultArea = new JTextArea();
        resultArea.setEditable(false);
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        resultArea.setMargin(new Insets(10, 10, 10, 10));
        searchPanel.add(UiComponentFactory.createSectionPanel("Search Results", "Matched documents and text previews are shown here.", new JScrollPane(resultArea)), BorderLayout.CENTER);

        searchStatusLabel = new JLabel(" ");
        searchStatusLabel.setForeground(new Color(75, 85, 99));
        searchProgressBar = new JProgressBar();
        searchProgressBar.setIndeterminate(true);
        searchProgressBar.setVisible(false);
        searchProgressBar.setPreferredSize(new Dimension(160, 18));

        JPanel searchStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        searchStatusPanel.add(searchProgressBar);
        searchStatusPanel.add(Box.createHorizontalStrut(8));
        searchStatusPanel.add(searchStatusLabel);
        searchPanel.add(searchStatusPanel, BorderLayout.SOUTH);
        return searchPanel;
    }

    public void setBusyStateManager(UiBusyStateManager busyStateManager) {
        this.busyStateManager = busyStateManager;
    }

    public JLabel getStatusLabel() {
        return searchStatusLabel;
    }

    public JProgressBar getProgressBar() {
        return searchProgressBar;
    }

    public List<JComponent> getBusySensitiveComponents() {
        List<JComponent> components = new ArrayList<>();
        components.add(searchButton);
        components.add(searchField);
        return components;
    }

    private void handleSearch() {
        if (busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }

        final String keyword = searchField.getText().trim().toLowerCase(Locale.ROOT);
        if (keyword.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "Please enter a keyword.", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        busyStateManager.setSearchBusy(true, "Searching for \"" + keyword + "\"...");
        new SwingWorker<SearchTaskResult, Void>() {
            @Override
            protected SearchTaskResult doInBackground() {
                try {
                    byte[] trapdoor = PEKSUtil.getTrapdoor(keyBundle.peksKey(), keyword);
                    ServerResponse response = serviceClient.search(trapdoor);
                    if (!response.isSuccess()) {
                        return SearchTaskResult.success(response.getMessage());
                    }

                    if (response.getData() instanceof List<?> rawResults) {
                        return SearchTaskResult.success(formatSearchResults(rawResults));
                    }
                    return SearchTaskResult.success("Unexpected response from server.");
                } catch (Exception ex) {
                    ex.printStackTrace();
                    return SearchTaskResult.failure("Search failed: " + DocumentOperationService.describeException(ex));
                }
            }

            @Override
            protected void done() {
                SearchTaskResult result;
                try {
                    result = get();
                } catch (Exception ex) {
                    ex.printStackTrace();
                    result = SearchTaskResult.failure("Search failed: " + DocumentOperationService.describeException(ex));
                }

                if (busyStateManager != null) {
                    busyStateManager.setSearchBusy(false, " ");
                }
                if (result.errorMessage() != null) {
                    JOptionPane.showMessageDialog(owner, result.errorMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                resultArea.setText(result.text());
                resultArea.setCaretPosition(0);
            }
        }.execute();
    }

    private String formatSearchResults(List<?> rawResults) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(rawResults.size()).append(" documents.\n\n");

        for (Object rawResult : rawResults) {
            EncryptedData result = (EncryptedData) rawResult;
            sb.append("DocID: ").append(result.getDocId()).append("\n");
            sb.append("File: ").append(result.getFileName()).append("\n");
            sb.append("Type: ").append(result.getMediaType()).append(" / ").append(result.getMimeType()).append("\n");
            sb.append("Size: ").append(result.getFileSize()).append(" bytes\n");
            if (operationService.isTextDocument(result) && result.getEncryptedContent() != null) {
                byte[] decryptedBytes = DESUtil.decrypt(result.getEncryptedContent(), keyBundle.desKey());
                sb.append("Content: ").append(new String(decryptedBytes, StandardCharsets.UTF_8)).append("\n");
            } else if (operationService.isTextDocument(result)) {
                sb.append("Content: Preview unavailable. Use the Download action to inspect the original file.\n");
            } else {
                sb.append("Content: Binary file restored via the Download action.\n");
            }
            sb.append("-------------------------\n");
        }

        return sb.toString();
    }

    private record SearchTaskResult(String text, String errorMessage) {
        private static SearchTaskResult success(String text) {
            return new SearchTaskResult(text, null);
        }

        private static SearchTaskResult failure(String errorMessage) {
            return new SearchTaskResult(null, errorMessage);
        }
    }
}
