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

import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.psi.PsiElement
import com.intellij.refactoring.RefactoringActionHandler
import com.tang.intellij.lua.refactoring.rename.LuaIntroduceVarHandler

/**
 *
 * Created by TangZX on 2016/12/20.
 */
class LuaRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean {
        return true
    }


    override fun getIntroduceVariableHandler(): RefactoringActionHandler? {
        return LuaIntroduceVarHandler()
    }

    override fun getIntroduceVariableHandler(element: PsiElement?): RefactoringActionHandler? {
        return LuaIntroduceVarHandler()
    }
}
