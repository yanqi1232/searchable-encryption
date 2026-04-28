package com.bdic.admin;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一管理客户端繁忙状态：控件禁用、进度条显示、状态文案与等待光标。
 */
public class UiBusyStateManager {

    private final JFrame owner;
    private final List<JComponent> disableWhenBusy = new ArrayList<>();
    private final JLabel uploadStatusLabel;
    private final JProgressBar uploadProgressBar;
    private final JLabel searchStatusLabel;
    private final JProgressBar searchProgressBar;
    private final JLabel documentsStatusLabel;
    private final JProgressBar documentsProgressBar;
    private boolean busy;

    public UiBusyStateManager(
            JFrame owner,
            JLabel uploadStatusLabel,
            JProgressBar uploadProgressBar,
            JLabel searchStatusLabel,
            JProgressBar searchProgressBar,
            JLabel documentsStatusLabel,
            JProgressBar documentsProgressBar
    ) {
        this.owner = owner;
        this.uploadStatusLabel = uploadStatusLabel;
        this.uploadProgressBar = uploadProgressBar;
        this.searchStatusLabel = searchStatusLabel;
        this.searchProgressBar = searchProgressBar;
        this.documentsStatusLabel = documentsStatusLabel;
        this.documentsProgressBar = documentsProgressBar;
    }

    public void registerBusySensitiveComponents(List<JComponent> components) {
        disableWhenBusy.clear();
        disableWhenBusy.addAll(components);
    }

    public boolean isBusy() {
        return busy;
    }

    public void setUploadBusy(boolean busy, String statusText) {
        setApplicationBusy(busy);
        toggleProgress(uploadProgressBar, busy);
        toggleProgress(searchProgressBar, false);
        toggleProgress(documentsProgressBar, false);
        setLabelText(uploadStatusLabel, statusText);
        if (!busy) {
            setLabelText(searchStatusLabel, " ");
            setLabelText(documentsStatusLabel, " ");
        }
    }

    public void setSearchBusy(boolean busy, String statusText) {
        setApplicationBusy(busy);
        toggleProgress(uploadProgressBar, false);
        toggleProgress(searchProgressBar, busy);
        toggleProgress(documentsProgressBar, false);
        setLabelText(searchStatusLabel, statusText);
        if (!busy) {
            setLabelText(uploadStatusLabel, " ");
            setLabelText(documentsStatusLabel, " ");
        }
    }

    public void setDocumentsBusy(boolean busy, String statusText) {
        setApplicationBusy(busy);
        toggleProgress(uploadProgressBar, false);
        toggleProgress(searchProgressBar, false);
        toggleProgress(documentsProgressBar, busy);
        setLabelText(documentsStatusLabel, statusText);
        if (!busy) {
            setLabelText(uploadStatusLabel, " ");
            setLabelText(searchStatusLabel, " ");
        }
    }

    public void updateUploadStatus(String statusText) {
        setLabelText(uploadStatusLabel, statusText);
    }

    public void updateSearchStatus(String statusText) {
        setLabelText(searchStatusLabel, statusText);
    }

    public void updateDocumentsStatus(String statusText) {
        setLabelText(documentsStatusLabel, statusText);
    }

    private void setApplicationBusy(boolean busy) {
        this.busy = busy;
        for (JComponent component : disableWhenBusy) {
            if (component != null) {
                component.setEnabled(!busy);
            }
        }
        owner.setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    private void toggleProgress(JProgressBar progressBar, boolean visible) {
        if (progressBar == null) {
            return;
        }
        progressBar.setVisible(visible);
        progressBar.setIndeterminate(visible);
    }

    private void setLabelText(JLabel label, String text) {
        if (label == null) {
            return;
        }
        label.setText(text == null || text.isBlank() ? " " : text);
    }
}
