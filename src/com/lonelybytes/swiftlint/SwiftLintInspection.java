package com.lonelybytes.swiftlint;

import com.intellij.codeInspection.*;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.lang.ASTNode;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.jetbrains.swift.psi.SwiftIdentifierPattern;
import com.jetbrains.swift.psi.SwiftParameter;
import com.jetbrains.swift.psi.SwiftVariableDeclaration;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR;
import static com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING;

//@com.intellij.openapi.components.State(
//    name = "com.appcodeplugins.swiftlint.v1_7",
//    storages = { @Storage(StoragePathMacros.WORKSPACE_FILE) }
//)
public class SwiftLintInspection extends LocalInspectionTool {
//        implements PersistentStateComponent<SwiftLintInspection.State>
    // State
    @SuppressWarnings("WeakerAccess")
    static class State {
        public String getAppPath() {
            return PropertiesComponent.getInstance().getValue("com.appcodeplugins.swiftlint.v1_7.appName");
        }

        public void setAppPath(String aAppPath) {
            PropertiesComponent.getInstance().setValue("com.appcodeplugins.swiftlint.v1_7.appName", aAppPath);
        }

        public boolean isQuickFixEnabled() {
            return PropertiesComponent.getInstance().getBoolean("com.appcodeplugins.swiftlint.v1_7.quickFixEnabled");
        }

        public void setQuickFixEnabled(boolean aQuickFixEnabled) {
            PropertiesComponent.getInstance().setValue("com.appcodeplugins.swiftlint.v1_7.quickFixEnabled", aQuickFixEnabled);
        }

        public boolean isDisableWhenNoConfigPresent() {
            return PropertiesComponent.getInstance().getBoolean("com.appcodeplugins.swiftlint.v1_7.isDisableWhenNoConfigPresent");
        }

        public void setDisableWhenNoConfigPresent(boolean aDisableWhenNoConfigPresent) {
            PropertiesComponent.getInstance().setValue("com.appcodeplugins.swiftlint.v1_7.isDisableWhenNoConfigPresent", aDisableWhenNoConfigPresent);
        }
    }
    
    @SuppressWarnings("WeakerAccess")
    static State STATE = new State();

//    @Nullable
//    @Override
//    public State getState() {
//        return STATE;
//    }
//
//    @Override
//    public void loadState(State aState) {
//        STATE = aState;
//    }

    // Main Class

    private static final String QUICK_FIX_NAME = "Autocorrect";
    private Map<String, Integer> _fileHashes = new HashMap<>();

    @Override
    public void inspectionStarted(@NotNull LocalInspectionToolSession session, boolean isOnTheFly) {
        super.inspectionStarted(session, isOnTheFly);
        saveAll();
    }

    @Override
    public boolean runForWholeFile() {
        return true;
    }

    @Nullable
    @Override
    public ProblemDescriptor[] checkFile(@NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        Document document = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (document == null || document.getLineCount() == 0 || !shouldCheck(file, document)) {
            return null;
        }

        if (STATE == null || STATE.getAppPath() == null) {
            return null;
        }

        if (STATE.isDisableWhenNoConfigPresent() && !weHaveSwiftLintConfigInProject(file.getProject(), 5)) {
            return null;
        }

        String toolPath = STATE.getAppPath();

        String toolOptions = "lint --path";

        Pattern errorsPattern = Pattern.compile("^(\\S.*?):(?:(\\d+):)(?:(\\d+):)? (\\S+):([^\\(]*)\\((.*)\\)$");
        int lineMatchIndex = 2;
        int columnMatchIndex = 3;
        int severityMatchIndex = 4;
        int messageMatchIndex = 5;
        int errorTypeMatchIndex = 6;

        List<ProblemDescriptor> descriptors = new ArrayList<>();

        try {
            String fileText = Utils.executeCommandOnFile(toolPath, toolOptions, file);

//            System.out.println("\n" + fileText + "\n");

            if (fileText.isEmpty()) {
                return descriptors.toArray(new ProblemDescriptor[descriptors.size()]);
            }

            Scanner scanner = new Scanner(fileText);

            String line;
            while (scanner.hasNext()) {
                line = scanner.nextLine();
                if (!line.contains(":")) {
                    continue;
                }

                Matcher matcher = errorsPattern.matcher(line);

                if (!matcher.matches()) {
                    continue;
                }

                final String errorType = matcher.group(errorTypeMatchIndex);

                int linePointerFix = errorType.equals("mark") ? -1 : -1;

                int lineNumber = Math.min(document.getLineCount() + linePointerFix, Integer.parseInt(matcher.group(lineMatchIndex)) + linePointerFix);
                lineNumber = Math.max(0, lineNumber);

                int columnNumber = matcher.group(columnMatchIndex) == null ? -1 : Math.max(0, Integer.parseInt(matcher.group(columnMatchIndex)));

                if (errorType.equals("empty_first_line")) {
                    // SwiftLint shows some strange identifier on the previous line
                    lineNumber += 1;
                    columnNumber = -1;
                }

                final String severity = matcher.group(severityMatchIndex);
                final String errorMessage = matcher.group(messageMatchIndex);

                int highlightStartOffset = document.getLineStartOffset(lineNumber);
                int highlightEndOffset = lineNumber < document.getLineCount() - 1
                        ? document.getLineStartOffset(lineNumber + 1)
                        : document.getLineEndOffset(lineNumber);

                TextRange range = TextRange.create(highlightStartOffset, highlightEndOffset);

                boolean weHaveAColumn = columnNumber > 0;

                if (weHaveAColumn) {
                    highlightStartOffset = Math.min(document.getTextLength() - 1, highlightStartOffset + columnNumber - 1);
                }

                CharSequence chars = document.getImmutableCharSequence();
                if (chars.length() <= highlightStartOffset) {
                    // This can happen when we browsing a file after it has been edited (some lines removed for example)
                    continue;
                }

                char startChar = chars.charAt(highlightStartOffset);
                PsiElement startPsiElement = file.findElementAt(highlightStartOffset);
                ASTNode startNode = startPsiElement == null ? null : startPsiElement.getNode();

                boolean isErrorInLineComment = startNode != null && startNode.getElementType().toString().equals("EOL_COMMENT");

                ProblemHighlightType highlightType = severityToHighlightType(severity);

                if (isErrorInLineComment) {
                    range = TextRange.create(document.getLineStartOffset(lineNumber), document.getLineEndOffset(lineNumber));
                } else {
                    boolean isErrorNewLinesOnly = (startChar == '\n');
                    boolean isErrorInSymbol = !Character.isLetterOrDigit(startChar) && !Character.isWhitespace(startChar);
                    isErrorInSymbol |= errorType.equals("opening_brace") || errorType.equals("colon");

                    if (!isErrorInSymbol) {
                        if (!isErrorNewLinesOnly && weHaveAColumn) {
                            // SwiftLint returns column for the previous non-space token, not the erroneous one. Let's try to correct it.
                            switch (errorType) {
                                case "unused_closure_parameter": {
                                    PsiElement psiElement = file.findElementAt(highlightStartOffset);
                                    range = psiElement != null ? psiElement.getTextRange() : range;
                                    break;
                                }
                                case "syntactic_sugar": {
                                    PsiElement psiElement = file.findElementAt(highlightStartOffset);
                                    if (psiElement != null) {
                                        psiElement = psiElement.getParent();
                                    }
                                    range = psiElement != null ? psiElement.getTextRange() : range;
                                    break;
                                }
                                case "variable_name":
                                    range = findVarInDefinition(file, highlightStartOffset, errorType);
                                    break;
                                case "type_name": {
                                    PsiElement psiElement = file.findElementAt(highlightStartOffset);
                                    range = psiElement != null ? psiElement.getTextRange() : range;
                                    break;
                                }
                                default:
                                    range = getNextTokenAtIndex(file, highlightStartOffset, errorType);
                                    break;
                            }
                        } else if (isErrorNewLinesOnly) {
                            // Let's select all empty lines here, we need to show that something is wrong with them
                            range = getEmptyLinesAroundIndex(document, highlightStartOffset);
                        }
                    } else {
                        PsiElement psiElement = file.findElementAt(highlightStartOffset);
                        if (psiElement != null) {
                            range = psiElement.getTextRange();

                            if (errorType.equals("colon")) {
                                range = getNextTokenAtIndex(file, highlightStartOffset, errorType);
                            }
                        }
                    }

                    if (errorType.equals("opening_brace") && Character.isWhitespace(startChar)) {
                        range = getNextTokenAtIndex(file, highlightStartOffset, errorType);
                    }

                    if (errorType.equals("valid_docs")) {
                        range = prevElement(file, highlightStartOffset).getTextRange();
                    }

                    if (errorType.equals("trailing_newline") && !weHaveAColumn && chars.charAt(chars.length() - 1) != '\n') {
                        highlightType = GENERIC_ERROR;
                        range = TextRange.create(highlightEndOffset - 1, highlightEndOffset);
                    }

                    if (isErrorNewLinesOnly) {
                        // Sometimes we need to highlight several returns. Usual error highlighting will not work in this case
                        highlightType = GENERIC_ERROR_OR_WARNING;
                    }
                }

                if (STATE.isQuickFixEnabled()) {
                    descriptors.add(manager.createProblemDescriptor(file, range, errorMessage.trim(), highlightType, false, new LocalQuickFix() {
                        @Nls
                        @NotNull
                        @Override
                        public String getName() {
                            return QUICK_FIX_NAME;
                        }

                        @Nls
                        @NotNull
                        @Override
                        public String getFamilyName() {
                            return QUICK_FIX_NAME;
                        }

                        @Override
                        public boolean startInWriteAction() {
                            return false;
                        }

                        @Override
                        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor problemDescriptor) {
                            WriteCommandAction writeCommandAction = new WriteCommandAction(project, file) {
                                @Override
                                protected void run(@NotNull Result aResult) throws Throwable {
                                    executeSwiftLintQuickFix(toolPath, file);
                                }
                            };

                            writeCommandAction.execute();
                        }
                    }));
                } else {
                    descriptors.add(manager.createProblemDescriptor(file, range, errorMessage.trim(), highlightType, false, LocalQuickFix.EMPTY_ARRAY));
                }
            }
        } catch (ProcessCanceledException ex) {
            // Do nothing here
        } catch (IOException ex) {
            if (ex.getMessage().contains("No such file or directory") || ex.getMessage().contains("error=2")) {
                Notifications.Bus.notify(new Notification(Configuration.KEY_SWIFTLINT, "Error", "Can't find swiftlint utility here:\n" + toolPath + "\nPlease check the path in settings.", NotificationType.ERROR));
            } else {
                Notifications.Bus.notify(new Notification(Configuration.KEY_SWIFTLINT, "Error", "IOException: " + ex.getMessage(), NotificationType.ERROR));
            }
        } catch (Exception ex) {
            Notifications.Bus.notify(new Notification(Configuration.KEY_SWIFTLINT, "Error", "Exception: " + ex.getMessage(), NotificationType.INFORMATION));
            ex.printStackTrace();
        }

        int newHash = document.getText().hashCode();
        _fileHashes.put(file.getVirtualFile().getCanonicalPath(), newHash);

        return descriptors.toArray(new ProblemDescriptor[descriptors.size()]);
    }

    private void executeSwiftLintQuickFix(String aToolPath, @NotNull PsiFile file) {
        saveAll();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                Utils.executeCommandOnFile(aToolPath, "autocorrect --path", file);
                LocalFileSystem.getInstance().refreshFiles(Collections.singletonList(file.getVirtualFile()));
            } catch (IOException aE) {
                Notifications.Bus.notify(new Notification(Configuration.KEY_SWIFTLINT, "Error", "Can't quick-fix.\nIOException: " + aE.getMessage(), NotificationType.ERROR));
            }
        });
    }

    private static class DepthedFile {
        int _depth;
        VirtualFile _file;

        DepthedFile(int aDepth, VirtualFile aFile) {
            _depth = aDepth;
            _file = aFile;
        }
    }

    private boolean weHaveSwiftLintConfigInProject(Project aProject, int aDepthToLookAt) {
        if (aProject.getBaseDir().findChild(".swiftlint.yml") != null) {
            return true;
        }

        List<DepthedFile> filesToLookAt = new LinkedList<>();
        filesToLookAt.addAll(
                Arrays.stream(aProject.getBaseDir().getChildren())
                        .filter(VirtualFile::isDirectory)
                        .map(aVirtualFile -> new DepthedFile(0, aVirtualFile))
                        .collect(Collectors.toList())
        );

        while (!filesToLookAt.isEmpty()) {
            DepthedFile file = filesToLookAt.get(0);
            filesToLookAt.remove(0);

            if (file._depth > aDepthToLookAt) {
                break;
            }

            if (file._file.findChild(".swiftlint.yml") != null) {
                return true;
            } else {
                filesToLookAt.addAll(
                        Arrays.stream(file._file.getChildren())
                                .filter(VirtualFile::isDirectory)
                                .map(aVirtualFile -> new DepthedFile(file._depth + 1, aVirtualFile))
                                .collect(Collectors.toList())
                );
            }
        }

        return false;
    }

    private void saveAll() {
        final FileDocumentManager documentManager = FileDocumentManager.getInstance();
        if (documentManager.getUnsavedDocuments().length != 0) {
            ApplicationManager.getApplication().invokeLater(documentManager::saveAllDocuments);
        }
    }

    private TextRange getEmptyLinesAroundIndex(Document aDocument, int aInitialIndex) {
        CharSequence chars = aDocument.getImmutableCharSequence();

        int from = aInitialIndex;
        while (from >= 0) {
            if (!Character.isWhitespace(chars.charAt(from))) {
                from += 1;
                break;
            }
            from -= 1;
        }

        int to = aInitialIndex;
        while (to < chars.length()) {
            if (!Character.isWhitespace(chars.charAt(to))) {
                to -= 1;
                break;
            }
            to += 1;
        }

        from = Math.max(0, from);

        if (from > 0 && chars.charAt(from) == '\n') {
            from += 1;
        }

        if (to > 0) {
            while (chars.charAt(to - 1) != '\n') {
                to -= 1;
            }
        }

        to = Math.max(from, to);

        return new TextRange(from, to);
    }

    private TextRange getNextTokenAtIndex(@NotNull PsiFile file, int aCharacterIndex, String aErrorType) {
        TextRange result = null;

        PsiElement psiElement;
        try {
            psiElement = file.findElementAt(aCharacterIndex);

            if (psiElement != null) {
                if (";".equals(psiElement.getText()) || (aErrorType.equals("variable_name") && psiElement.getNode().getElementType().toString().equals("IDENTIFIER"))) {
                    result = psiElement.getTextRange();
                } else {
                    result = psiElement.getNode().getTextRange();

                    psiElement = nextElement(file, aCharacterIndex);

                    if (psiElement != null) {
                        if (psiElement.getContext() != null && psiElement.getContext().getNode().getElementType().toString().equals("OPERATOR_SIGN")) {
                            result = psiElement.getContext().getNode().getTextRange();
                        } else {
                            result = psiElement.getNode().getTextRange();
                        }
                    }
                }
            }
        } catch (ProcessCanceledException aE) {
            // Do nothing
        } catch (Exception aE) {
            aE.printStackTrace();
        }

        return result;
    }

    private TextRange findVarInDefinition(@NotNull PsiFile file, int aCharacterIndex, String aErrorType) {
        TextRange result = null;

        PsiElement psiElement;
        try {
            psiElement = file.findElementAt(aCharacterIndex);

            while (psiElement != null &&
                    !(psiElement instanceof SwiftVariableDeclaration) &&
                    !(psiElement instanceof SwiftParameter)) {
                psiElement = psiElement.getParent();
            }

            if (psiElement != null) {
                if (psiElement instanceof SwiftVariableDeclaration) {
                    SwiftVariableDeclaration variableDeclaration = (SwiftVariableDeclaration) psiElement;
                    SwiftIdentifierPattern identifierPattern = variableDeclaration.getVariables().get(0);
                    result = identifierPattern.getNode().getTextRange();
                } else /*if (psiElement instanceof SwiftParameter)*/ {
                    SwiftParameter variableDeclaration = (SwiftParameter) psiElement;
                    result = variableDeclaration.getNode().getTextRange();
                }
            }
        } catch (ProcessCanceledException aE) {
            // Do nothing
        } catch (Exception aE) {
            aE.printStackTrace();
        }

        return result;
    }

    private PsiElement nextElement(PsiFile aFile, int aElementIndex) {
        PsiElement nextElement = null;

        PsiElement initialElement = aFile.findElementAt(aElementIndex);

        if (initialElement != null) {
            int index = aElementIndex + initialElement.getTextLength();
            nextElement = aFile.findElementAt(index);

            while (nextElement != null && (nextElement == initialElement || nextElement instanceof PsiWhiteSpace)) {
                index += nextElement.getTextLength();
                nextElement = aFile.findElementAt(index);
            }
        }

        return nextElement;
    }

    private PsiElement prevElement(PsiFile aFile, int aElementIndex) {
        PsiElement nextElement = null;

        PsiElement initialElement = aFile.findElementAt(aElementIndex);

        if (initialElement != null) {
            int index = initialElement.getTextRange().getStartOffset() - 1;
            nextElement = aFile.findElementAt(index);

            while (nextElement != null && (nextElement == initialElement || nextElement instanceof PsiWhiteSpace)) {
                index = nextElement.getTextRange().getStartOffset() - 1;
                if (index >= 0) {
                    nextElement = aFile.findElementAt(index);
                } else {
                    break;
                }
            }
        }

        return nextElement;
    }

    private boolean shouldCheck(@NotNull final PsiFile aFile, @NotNull final Document aDocument) {
        boolean isExtensionSwifty = "swift".equalsIgnoreCase(aFile.getVirtualFile().getExtension());

        Integer previousHash = _fileHashes.get(aFile.getVirtualFile().getCanonicalPath());
        int newHash = aDocument.getText().hashCode();
        boolean fileChanged = previousHash == null || previousHash != newHash;

        return isExtensionSwifty;
    }

    private static ProblemHighlightType severityToHighlightType(@NotNull final String severity) {
        switch (severity.trim().toLowerCase()) {
            case "error":
                return GENERIC_ERROR;
            case "warning":
                return GENERIC_ERROR_OR_WARNING;
            case "style":
            case "performance":
            case "portability":
                return ProblemHighlightType.LIKE_UNKNOWN_SYMBOL;
            case "information":
                return ProblemHighlightType.LIKE_UNKNOWN_SYMBOL;
            default:
                return ProblemHighlightType.LIKE_UNKNOWN_SYMBOL;
        }
    }
}
