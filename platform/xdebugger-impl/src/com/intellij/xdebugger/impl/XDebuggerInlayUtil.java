/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.xdebugger.impl;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.FontPreferences;
import com.intellij.openapi.editor.impl.ComplementaryFontsRegistry;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.editor.impl.FontInfo;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.util.DocumentUtil;
import com.intellij.util.ui.UIUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XValue;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl;
import com.intellij.xdebugger.ui.DebuggerColors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public class XDebuggerInlayUtil {
  public static final Key<Helper> HELPER_KEY = Key.create("xdebug.inlay.helper");

  private static int getIdentifierEndOffset(@NotNull CharSequence text, int startOffset) {
    while (startOffset < text.length() && Character.isJavaIdentifierPart(text.charAt(startOffset))) startOffset++;
    return startOffset;
  }

  public static void createInlay(@NotNull Project project, @NotNull VirtualFile file, int offset, String inlayText) {
    UIUtil.invokeLaterIfNeeded(() -> {
      FileEditor editor = FileEditorManager.getInstance(project).getSelectedEditor(file);
      if (editor instanceof TextEditor) {
        Editor e = ((TextEditor)editor).getEditor();
        CharSequence text = e.getDocument().getImmutableCharSequence();
        int insertOffset = getIdentifierEndOffset(text, offset);
        List<Inlay> existing = e.getInlayModel().getInlineElementsInRange(insertOffset, insertOffset);
        for (Inlay inlay : existing) {
          if (inlay.getRenderer() instanceof MyRenderer) {
            Disposer.dispose(inlay);
          }
        }

        e.getInlayModel().addInlineElement(insertOffset, new MyRenderer(inlayText));
      }
    });
  }

  public static void clearInlays(@NotNull Project project) {
    UIUtil.invokeLaterIfNeeded(() -> {
      FileEditor[] editors = FileEditorManager.getInstance(project).getAllEditors();
      for (FileEditor editor : editors) {
        if (editor instanceof TextEditor) {
          Editor e = ((TextEditor)editor).getEditor();
          List<Inlay> existing = e.getInlayModel().getInlineElementsInRange(0, e.getDocument().getTextLength());
          for (Inlay inlay : existing) {
            if (inlay.getRenderer() instanceof MyRenderer) {
              Disposer.dispose(inlay);
            }
          }
        }
      }
    });
  }

  public static void setupValuePlaceholders(@NotNull XDebugSessionImpl session, boolean removePlaceholders) {
    XSourcePosition position = removePlaceholders ? null : session.getCurrentPosition();
    if (!removePlaceholders && position == null) return;

    XDebuggerInlayUtil.Helper helper = session.getSessionData().getUserData(HELPER_KEY);
    if (helper == null) return;

    Project project = session.getProject();
    PsiDocumentManager.getInstance(project).performLaterWhenAllCommitted(() -> helper.setupValuePlaceholders(project, position));
  }

  public static boolean showValueInBlockInlay(@NotNull XDebugSessionImpl session,
                                              @NotNull XValueNodeImpl node,
                                              @NotNull XSourcePosition position,
                                              @NotNull XValue value,
                                              @NotNull XValuePresentation presentation) {
    XDebuggerInlayUtil.Helper helper = session.getSessionData().getUserData(HELPER_KEY);
    return helper != null && helper.showValueInBlockInlay(session.getProject(), node, position, value, presentation);
  }

  public static void createBlockInlay(@NotNull Editor editor, int offset) {
    editor.getInlayModel().addBlockElement(offset, false, false, 0, new MyBlockRenderer());
  }

  public static void addValueToBlockInlay(@NotNull Editor editor, int offset, String inlayText) {
    int lineStartOffset = DocumentUtil.getLineStartOffset(offset, editor.getDocument());
    List<Inlay> inlays = editor.getInlayModel().getBlockElementsInRange(lineStartOffset, lineStartOffset);
    if (inlays.size() != 1) return;
    Inlay inlay = inlays.get(0);
    CharSequence text = editor.getDocument().getImmutableCharSequence();
    int identifierEndOffset = getIdentifierEndOffset(text, offset);
    ((MyBlockRenderer)inlay.getRenderer()).addValue(offset, identifierEndOffset, inlayText);
    inlay.updateSize();
  }

  public static void clearBlockInlays(@NotNull Editor editor) {
    List<Inlay> existing = editor.getInlayModel().getBlockElementsInRange(0, editor.getDocument().getTextLength());
    for (Inlay inlay : existing) {
      if (inlay.getRenderer() instanceof MyBlockRenderer) {
        Disposer.dispose(inlay);
      }
    }
  }

  public interface Helper {
    void setupValuePlaceholders(@NotNull Project project, @Nullable XSourcePosition currentPosition);
    boolean showValueInBlockInlay(@NotNull Project project, @NotNull XValueNodeImpl node, @NotNull XSourcePosition position,
                                  @NotNull XValue value, @NotNull XValuePresentation presentation);
  }

  private static class MyBlockRenderer implements EditorCustomElementRenderer  {
    private final SortedSet<ValueInfo> values = new TreeSet<>();

    private void addValue(int refStartOffset, int refEndOffset, @NotNull String value) {
      ValueInfo info = new ValueInfo(refStartOffset, refEndOffset, value);
      values.remove(info);
      values.add(info); // retain latest reported value for given offset
    }

    @Override
    public void paint(@NotNull Inlay inlay,
                      @NotNull Graphics g,
                      @NotNull Rectangle targetRegion,
                      @NotNull TextAttributes textAttributes) {
      if (values.isEmpty()) return;
      Editor editor = inlay.getEditor();
      EditorColorsScheme colorsScheme = editor.getColorsScheme();
      TextAttributes attributes = colorsScheme.getAttributes(DebuggerColors.INLINED_VALUES_EXECUTION_LINE);
      if (attributes == null) return;
      Color fgColor = attributes.getForegroundColor();
      if (fgColor == null) return;
      g.setColor(fgColor);
      g.setFont(new Font(colorsScheme.getEditorFontName(), attributes.getFontType(), colorsScheme.getEditorFontSize()));

      int curX = 0;
      for (ValueInfo value : values) {
        curX += 5; // minimum gap between values
        int xStart = editor.offsetToXY(value.refStartOffset, true, false).x;
        int xEnd = editor.offsetToXY(value.refEndOffset, false, true).x;
        int width = g.getFontMetrics().stringWidth(value.value);
        curX = Math.max(curX, (xStart + xEnd - width) / 2);
        g.drawString(value.value, curX, targetRegion.y + ((EditorImpl)editor).getAscent());
        g.drawLine(Math.min(xEnd, Math.max(xStart, curX + width / 2)), targetRegion.y, curX + width / 2, targetRegion.y + 2);
        g.drawLine(curX, targetRegion.y + 2, curX + width, targetRegion.y + 2);
        curX += width;
      }
    }

    private static class ValueInfo implements Comparable<ValueInfo> {
      private final int refStartOffset;
      private final int refEndOffset;
      private final String value;

      private ValueInfo(int refStartOffset, int refEndOffset, String value) {
        this.refStartOffset = refStartOffset;
        this.refEndOffset = refEndOffset;
        this.value = value;
      }

      @Override
      public int compareTo(@NotNull ValueInfo o) {
        return refStartOffset - o.refStartOffset;
      }
    }
  }

  private static class MyRenderer implements EditorCustomElementRenderer {
    private final String myText;

    private MyRenderer(String text) {
      myText = "(" + text + ")";
    }

    private static FontInfo getFontInfo(@NotNull Editor editor) {
      EditorColorsScheme colorsScheme = editor.getColorsScheme();
      FontPreferences fontPreferences = colorsScheme.getFontPreferences();
      TextAttributes attributes = editor.getColorsScheme().getAttributes(DebuggerColors.INLINED_VALUES_EXECUTION_LINE);
      int fontStyle = attributes == null ? Font.PLAIN : attributes.getFontType();
      return ComplementaryFontsRegistry.getFontAbleToDisplay('a', fontStyle, fontPreferences,
                                                             FontInfo.getFontRenderContext(editor.getContentComponent()));
    }

    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
      FontInfo fontInfo = getFontInfo(inlay.getEditor());
      return fontInfo.fontMetrics().stringWidth(myText);
    }

    @Override
    public void paint(@NotNull Inlay inlay, @NotNull Graphics g, @NotNull Rectangle r, @NotNull TextAttributes textAttributes) {
      Editor editor = inlay.getEditor();
      TextAttributes attributes = editor.getColorsScheme().getAttributes(DebuggerColors.INLINED_VALUES_EXECUTION_LINE);
      if (attributes == null) return;
      Color fgColor = attributes.getForegroundColor();
      if (fgColor == null) return;
      g.setColor(fgColor);
      FontInfo fontInfo = getFontInfo(editor);
      g.setFont(fontInfo.getFont());
      FontMetrics metrics = fontInfo.fontMetrics();
      g.drawString(myText, r.x, r.y + metrics.getAscent());
    }
  }
}
