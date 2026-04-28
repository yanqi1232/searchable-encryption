package com.bdic.admin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Windows 原生对话框工具，负责文件夹选择等系统级交互。
 */
public final class NativeDialogHelper {

    private NativeDialogHelper() {
    }

    public static String chooseFolder(String description) {
        String script = "$dialog = New-Object System.Windows.Forms.FolderBrowserDialog; "
                + "$dialog.Description = '" + escapePowerShellSingleQuoted(description) + "'; "
                + "$dialog.ShowNewFolderButton = $false; "
                + "if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { "
                + "Write-Output ([Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($dialog.SelectedPath))) }";
        ProcessBuilder processBuilder = new ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-STA",
                "-Command",
                "Add-Type -AssemblyName System.Windows.Forms; " + script
        );
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            String output;
            try (var inputStream = process.getInputStream()) {
                output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            int exitCode = process.waitFor();
            if (exitCode != 0 || output.isBlank()) {
                return null;
            }
            String encodedPath = output.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (encodedPath == null || encodedPath.isBlank()) {
                return null;
            }
            return new String(Base64.getDecoder().decode(encodedPath), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String escapePowerShellSingleQuoted(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
