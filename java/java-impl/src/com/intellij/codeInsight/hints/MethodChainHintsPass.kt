// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.hints

import com.intellij.codeHighlighting.EditorBoundHighlightingPass
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.daemon.impl.HintRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.CaretVisualPositionKeeper
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.psi.*
import com.intellij.util.DocumentUtil
import com.siyeh.ig.psiutils.ExpressionUtils
import gnu.trove.TIntObjectHashMap

class MethodChainHintsPass(private val rootElement: PsiElement, editor: Editor) : EditorBoundHighlightingPass(editor,
                                                                                                              rootElement.containingFile,
                                                                                                              true) {
  private val hints = TIntObjectHashMap<String>()
  private val traverser: SyntaxTraverser<PsiElement> = SyntaxTraverser.psiTraverser(rootElement)

  override fun doCollectInformation(progress: ProgressIndicator) {
    assert(myDocument != null)
    hints.clear()

    val virtualFile = rootElement.containingFile?.originalFile?.virtualFile
    if (virtualFile != null && CodeInsightSettings.getInstance().SHOW_METHOD_CHAIN_TYPES_INLINE) {
      traverser.forEach { process(it) }
    }
  }

  private fun process(element: PsiElement) {
    val call = element as? PsiMethodCallExpression ?: return
    val qualifier = call.methodExpression.qualifierExpression
    if (qualifier != null && qualifier is PsiMethodCallExpression) {
      val callSibling = qualifier.nextSibling
      if (callSibling is PsiWhiteSpace && callSibling.textContains('\n')) return // Not first call
    }
    val next = call.nextSibling
    if (!(next is PsiWhiteSpace && next.textContains('\n'))) return
    val chain = collectChain(call)
      .filter {
        val nextSibling = it.nextSibling as? PsiWhiteSpace ?: return@filter false
        nextSibling.textContains('\n')
      }
    if (chain.isEmpty()) return
    val types = chain.mapNotNull { it.type }
    if (types.size != chain.size) return // some type unknown

    val uniqueTypes = mutableSetOf<PsiType>()
    for (i in (0 until types.size - 1)) { // Except last to avoid builder.build() which has obvious type
      uniqueTypes.add(types[i])
    }
    if (uniqueTypes.size < 2) return // to hide hints for builders, where type is obvious
    for ((index, currentCall) in chain.withIndex()) {
      val offset = currentCall.textRange.endOffset
      hints.put(offset, types[index].presentableText)
    }
  }

  private fun collectChain(call: PsiMethodCallExpression): List<PsiMethodCallExpression> {
    val chain = mutableListOf(call)
    var current = call
    while (true) {
      val nextCall = ExpressionUtils.getCallForQualifier(current)
      if (nextCall == null) break
      chain.add(nextCall)
      current = nextCall
    }
    return chain
  }

  override fun doApplyInformationToEditor() {
    val keeper = CaretVisualPositionKeeper(myEditor)

    val inlayModel = myEditor.inlayModel

    val toRemove = inlayModel.getInlineElementsInRange(rootElement.textRange.startOffset + 1, rootElement.textRange.endOffset - 1)
      .filter { METHOD_CHAIN_INLAY_KEY.isIn(it) }
      .filter { inlay ->
        val inlayText = hints.get(inlay.offset)
        inlayText == null || inlayText == (inlay.renderer as MethodChainHintRenderer).text // not changed inlays
      }

    DocumentUtil.executeInBulk(myEditor.document, toRemove.size + hints.values.count() > 1000) {
      toRemove.forEach { Disposer.dispose(it) }

      hints.forEachEntry { offset, inlayText ->
        val inlay = inlayModel.addInlineElement(offset, MethodChainHintRenderer(inlayText))
        inlay?.putUserData(METHOD_CHAIN_INLAY_KEY, true)
        true
      }
    }

    keeper.restoreOriginalLocation(false)

    if (rootElement === myFile) {
      AnnotationHintsPassFactory.putCurrentModificationStamp(myEditor, myFile)
    }
  }

  companion object {
    private val METHOD_CHAIN_INLAY_KEY = Key.create<Boolean>("METHOD_CHAIN_INLAY_KEY")
  }

  private class MethodChainHintRenderer(text: String) : HintRenderer(text) {
    override fun getContextMenuGroupId() = "MethodChainHintsContextMenu"
  }
}