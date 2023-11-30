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

package com.tang.intellij.lua.usages;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Segment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.usages.ReadWriteAccessUsageInfo2UsageAdapter;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageTarget;
import com.intellij.usages.rules.UsageFilteringRule;
import com.intellij.usages.rules.UsageFilteringRuleProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

public class LuaUsageFilteringRuleProvider implements UsageFilteringRuleProvider {

    @Override
    public @NotNull Collection<? extends @NotNull UsageFilteringRule> getApplicableRules(@NotNull Project project) {
        return List.of(new LuaOverrideFilteringRule());
    }

    class LuaOverrideFilteringRule implements UsageFilteringRule {

        @Override
        public boolean isVisible(@NotNull Usage usage, @NotNull UsageTarget @NotNull [] targets) {
            if (usage instanceof ReadWriteAccessUsageInfo2UsageAdapter) {
                var adapter = (ReadWriteAccessUsageInfo2UsageAdapter) usage;
                final PsiElement psiElement = adapter.getElement();
                if (psiElement != null) {
                    Segment range = adapter.getNavigationRange();
                    PsiElement preElement = psiElement.getContainingFile().findElementAt(range.getStartOffset() - 1);
                    PsiElement leaf = PsiTreeUtil.prevLeaf(preElement);
                    return !"__super".equals(leaf.getText());
                }

            }
            return true;
        }

        @Override
        public @NotNull String getActionId() {
            return "UsageFiltering.LuaOverride";
        }
    }


}
