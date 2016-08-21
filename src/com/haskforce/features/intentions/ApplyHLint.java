package com.haskforce.features.intentions;

import com.haskforce.psi.HaskellPpragma;
import com.haskforce.psi.HaskellTypes;
import com.haskforce.psi.impl.HaskellElementFactory;
import com.haskforce.utils.FileUtil;
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.lang.ASTNode;
import com.intellij.lang.FileASTNode;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.tree.TokenSet;
import com.intellij.util.Function;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

public class ApplyHLint extends BaseIntentionAction {
    public final String hint;
    public final String to;
    public final int start;
    public final int end;

    public ApplyHLint(String hint, String to, int start, int end) {
        this.hint = hint;
        this.to = to;
        this.start = start;
        this.end = end;
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "Change as suggested";
    }

    @NotNull
    @Override
    public String getText()  {
        return "Change to " + to;
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return true;
    }

    @Override
    public void invoke(@NotNull final Project project, Editor editor, final PsiFile file) throws IncorrectOperationException {
        FileUtil.updateFileText(project, file, new Function<String, String>() {
            public String fun(String text) {
                final String before = text.substring(0, start);
                final String after = text.substring(end);
                return before + to + after;
            }
        });
    }
}
