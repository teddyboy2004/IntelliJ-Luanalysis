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

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.navigation.NavigationItemFileStatus;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Iconable;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.Segment;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.*;
import com.intellij.usages.impl.FileStructureGroupRuleProvider;
import com.intellij.usages.rules.PsiElementUsage;
import com.intellij.usages.rules.SingleParentUsageGroupingRule;
import com.intellij.usages.rules.UsageGroupingRule;
import com.tang.intellij.lua.psi.LuaFuncBodyOwner;
import com.tang.intellij.lua.psi.LuaLocalFuncDefStat;
import com.tang.intellij.lua.psi.LuaPsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;
import java.util.Objects;

public class UsageGroupingRuleProvider implements FileStructureGroupRuleProvider {
    @Override
    public @Nullable UsageGroupingRule getUsageGroupingRule(@NotNull Project project) {
        return new LuaFuncGroupingRule(UsageViewSettings.getInstance());
    }

    @Override
    public UsageGroupingRule getUsageGroupingRule(@NotNull Project project, @NotNull UsageViewSettings usageViewSettings) {
        return FileStructureGroupRuleProvider.super.getUsageGroupingRule(project, usageViewSettings);
    }
}

class LuaFuncGroupingRule extends SingleParentUsageGroupingRule {
    private static final Logger LOG = Logger.getInstance(LuaFuncGroupingRule.class);
    @NotNull
    private final UsageViewSettings myUsageViewSettings;

    public LuaFuncGroupingRule(@NotNull UsageViewSettings usageViewSettings) {
        myUsageViewSettings = usageViewSettings;
    }

    @Nullable
    @Override
    protected UsageGroup getParentGroupFor(@NotNull Usage usage, UsageTarget @NotNull [] targets) {
        if (!(usage instanceof PsiElementUsage)) return null;
        PsiElement psiElement = ((PsiElementUsage) usage).getElement();
        PsiFile containingFile = psiElement.getContainingFile();
        if (containingFile == null) return null;
        InjectedLanguageManager manager = InjectedLanguageManager.getInstance(containingFile.getProject());
        PsiFile topLevelFile = manager.getTopLevelFile(containingFile);
        if (topLevelFile instanceof LuaPsiFile && !topLevelFile.getFileType().isBinary()) {
            PsiElement containingFunc = topLevelFile == containingFile ? psiElement : manager.getInjectionHost(containingFile);
            if (usage instanceof UsageInfo2UsageAdapter && topLevelFile == containingFile) {
                int offset = ((UsageInfo2UsageAdapter) usage).getUsageInfo().getNavigationOffset();
                containingFunc = containingFile.findElementAt(offset);
            }
            PsiElement lastContainFunc = null;
            do {
                containingFunc = PsiTreeUtil.getParentOfType(containingFunc, LuaFuncBodyOwner.class, true);
                if (containingFunc == null)
                {
                    break;
                }
                lastContainFunc = containingFunc;
                if (containingFunc instanceof LuaLocalFuncDefStat) {
                    continue;
                }
                if (((LuaFuncBodyOwner<?>) containingFunc).getName() != null) {
                    break;
                }
            } while (true);

            if (containingFunc != null) {
                return new LuaFuncUsageGroup((LuaFuncBodyOwner) containingFunc, myUsageViewSettings);
            }
            else if (lastContainFunc!= null)
            {
                return new LuaFuncUsageGroup((LuaFuncBodyOwner) lastContainFunc, myUsageViewSettings);
            }
        }
        return null;
    }

    private static class LuaFuncUsageGroup implements UsageGroup, DataProvider {
        private final SmartPsiElementPointer<LuaFuncBodyOwner> myFuncPointer;
        private final String myName;
        private final Icon myIcon;
        private final Project myProject;

        @NotNull
        private final UsageViewSettings myUsageViewSettings;

        LuaFuncUsageGroup(LuaFuncBodyOwner psiFunc, @NotNull UsageViewSettings usageViewSettings) {
            myName = psiFunc.getName() == null ? "" : psiFunc.getName();
            myProject = psiFunc.getProject();
            myFuncPointer = SmartPointerManager.getInstance(myProject).createSmartPsiElementPointer(psiFunc);

            myIcon = getIconImpl(psiFunc);

            myUsageViewSettings = usageViewSettings;
        }

        private static Icon getIconImpl(LuaFuncBodyOwner psiFunc) {
            return psiFunc.getIcon(Iconable.ICON_FLAG_VISIBILITY | Iconable.ICON_FLAG_READ_STATUS);
        }

        public int hashCode() {
            return myName.hashCode();
        }

        public boolean equals(Object object) {
            if (!(object instanceof LuaFuncUsageGroup)) {
                return false;
            }
            LuaFuncUsageGroup group = (LuaFuncUsageGroup) object;
            return Objects.equals(myName, ((LuaFuncUsageGroup) object).myName) && SmartPointerManager.getInstance(myProject).pointToTheSameElement(myFuncPointer, group.myFuncPointer);
        }

        @Override
        public Icon getIcon() {
            return myIcon;
        }

        private LuaFuncBodyOwner getFunc() {
            return myFuncPointer.getElement();
        }

        @Override
        @NotNull
        public String getPresentableGroupText() {
            return myName;
        }

        @Override
        public FileStatus getFileStatus() {
            if (myFuncPointer.getProject().isDisposed()) return null;
            PsiFile file = myFuncPointer.getContainingFile();
            return file == null ? null : NavigationItemFileStatus.get(file);
        }

        @Override
        public boolean isValid() {
            final var meFunc = getFunc();
            return meFunc != null && meFunc.isValid();
        }

        @Override
        public void navigate(boolean focus) throws UnsupportedOperationException {
            if (canNavigate()) {
                getFunc().navigate(focus);
            }
        }

        @Override
        public boolean canNavigate() {
            return isValid();
        }

        @Override
        public boolean canNavigateToSource() {
            return canNavigate();
        }

        @Override
        public int compareTo(@NotNull UsageGroup usageGroup) {
            if (!(usageGroup instanceof LuaFuncUsageGroup)) {
                LOG.error("LuaFuncUsageGroup expected but " + usageGroup.getClass() + " found");
            }
            LuaFuncUsageGroup other = (LuaFuncUsageGroup) usageGroup;
            if (SmartPointerManager.getInstance(myProject).pointToTheSameElement(myFuncPointer, other.myFuncPointer)) {
                return 0;
            }
            if (!myUsageViewSettings.isSortAlphabetically()) {
                Segment segment1 = myFuncPointer.getRange();
                Segment segment2 = other.myFuncPointer.getRange();
                if (segment1 != null && segment2 != null) {
                    return segment1.getStartOffset() - segment2.getStartOffset();
                }
            }

            return myName.compareToIgnoreCase(other.myName);
        }

        @Nullable
        @Override
        public Object getData(@NotNull String dataId) {
            if (PlatformCoreDataKeys.SLOW_DATA_PROVIDERS.is(dataId)) {
                return List.of((DataProvider) this::getSlowData);
            }
            return null;
        }

        @Nullable
        private Object getSlowData(@NotNull String dataId) {
            if (CommonDataKeys.PSI_ELEMENT.is(dataId)) {
                return getFunc();
            } else if (UsageView.USAGE_INFO_KEY.is(dataId)) {
                var func = getFunc();
                return func == null ? null : new UsageInfo(func);
            }
            return null;
        }
    }
}