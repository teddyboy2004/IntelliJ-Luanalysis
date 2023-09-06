/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
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

package com.tang.intellij.lua.editor

import com.intellij.execution.filters.FilterMixin.AdditionalHighlight
import com.intellij.icons.AllIcons
import com.intellij.ide.IdeBundle
import com.intellij.ide.structureView.*
import com.intellij.ide.util.InheritedMembersNodeProvider
import com.intellij.ide.util.treeView.smartTree.*
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.tang.intellij.lua.comment.psi.LuaDocGeneralTy
import com.tang.intellij.lua.comment.psi.LuaDocTagClass
import com.tang.intellij.lua.editor.structure.*
import com.tang.intellij.lua.psi.LuaPsiFile


/**
 * Structure View
 * Created by TangZX on 2016/12/13.
 */
class LuaStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        return object : TreeBasedStructureViewBuilder() {

            override fun createStructureViewModel(editor: Editor?): StructureViewModel {
                return LuaStructureViewModel(psiFile)
            }
        }
    }

    inner class LuaStructureViewModel(psiFile: PsiFile) : StructureViewModelBase(psiFile, LuaFileElement(psiFile as LuaPsiFile)), StructureViewModel.ElementInfoProvider {
        init {
            withSorters(LuaAlphaSorter())
        }

        private val NODE_PROVIDERS: Collection<NodeProvider<TreeElement>> = listOf(
            LuaInheritedMembersNodeProvider(),
        )

        override fun isAlwaysShowsPlus(structureViewTreeElement: StructureViewTreeElement): Boolean {
            return false
        }

        override fun isAlwaysLeaf(structureViewTreeElement: StructureViewTreeElement): Boolean {
            return false
        }

        override fun getNodeProviders(): Collection<NodeProvider<TreeElement>> {
            return NODE_PROVIDERS
        }
    }

    /**
     * 字母alpha排序，但field排在method前面
     */
    inner class LuaAlphaSorter : Sorter {

        override fun getComparator() = kotlin.Comparator<Any> { o1, o2 ->
            if (o1 is LuaClassFieldElement && o2 is LuaFuncElement)
                return@Comparator -1
            else if (o1 is LuaFuncElement && o2 is LuaClassFieldElement)
                return@Comparator 1

            val s1 = SorterUtil.getStringPresentation(o1)
            val s2 = SorterUtil.getStringPresentation(o2)
            s1.compareTo(s2, ignoreCase = true)
        }

        override fun isVisible(): Boolean {
            return true
        }

        override fun getPresentation(): ActionPresentation {
            return ActionPresentationData(IdeBundle.message("action.sort.alphabetically"), IdeBundle.message("action.sort.alphabetically"), AllIcons.ObjectBrowser.Sorted)
        }

        override fun getName(): String {
            return "Alpha Sorter"
        }
    }

    inner class LuaInheritedMembersNodeProvider : InheritedMembersNodeProvider<TreeElement>() {
        override fun provideNodes(node: TreeElement): Collection<TreeElement> {
            if (node is LuaClassElement) {
                val inherited = mutableListOf<TreeElement>()
                val containNames = hashSetOf<String>()

                addInheritedMembers(node, containNames, inherited)
                return inherited
            }
            return emptyList()
        }

        private fun addInheritedMembers(
            node: LuaClassElement?,
            containNames: HashSet<String>,
            inherited: MutableList<TreeElement>
        ) {
            if(node == null || node.element !is LuaDocTagClass)
            {
                return
            }
            val clazz = node.element
            val superClass = clazz.superClass
            if (superClass is LuaDocGeneralTy) {
                node.children.forEach { treeElement -> containNames.add((treeElement as LuaTreeElement).name) }
                val psiElement = superClass.typeRef.reference.resolve()
                if (psiElement != null && psiElement.containingFile is LuaPsiFile) {
                    val element = LuaFileElement(psiElement.containingFile as LuaPsiFile)
                    val find = element.children.find { treeElement -> (treeElement as LuaTreeElement).name == superClass.text }
                    if (find is LuaClassElement)
                    {
                        find.children.forEach { treeElement ->
                            val luaTreeElement = treeElement as LuaTreeElement
                            val name = luaTreeElement.name
                            if (containNames.add(name)) {
                                luaTreeElement.inherited = true
                                inherited.add(luaTreeElement)
                            }
                        }
                        addInheritedMembers(find, containNames, inherited)
                    }

                }
            }
        }

    }
}
