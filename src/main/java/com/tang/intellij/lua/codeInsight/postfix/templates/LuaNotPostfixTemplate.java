/*
 * Copyright (c) 2023
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

package com.tang.intellij.lua.codeInsight.postfix.templates;

import com.intellij.codeInsight.template.postfix.templates.StringBasedPostfixTemplate;
import com.intellij.psi.PsiElement;
import com.tang.intellij.lua.psi.LuaExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.tang.intellij.lua.codeInsight.postfix.LuaPostfixUtils.selectorAllExpressionsWithCurrentOffset;

public class LuaNotPostfixTemplate extends StringBasedPostfixTemplate {

    public LuaNotPostfixTemplate() {
        super("not", "not expr", selectorAllExpressionsWithCurrentOffset(psiElement -> psiElement instanceof LuaExpression));
    }

    @Override
    public @Nullable String getTemplateString(@NotNull PsiElement element) {
        return "not $expr$$END$";
    }

    @Override
    protected PsiElement getElementToRemove(PsiElement expr) {
        return expr;
    }
}
