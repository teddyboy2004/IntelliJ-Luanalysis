// This is a generated file. Not intended for manual editing.
package com.tang.intellij.lua.comment.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import com.tang.intellij.lua.comment.psi.LuaDocPsiImplUtilKt;
import com.tang.intellij.lua.comment.psi.LuaDocTy;
import com.tang.intellij.lua.comment.psi.LuaDocUnionTy;
import com.tang.intellij.lua.comment.psi.LuaDocVisitor;
import com.tang.intellij.lua.project.LuaSettings;
import com.tang.intellij.lua.ty.ITy;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;

public class LuaDocUnionTyImpl extends LuaDocTyImpl implements LuaDocUnionTy {

    public static HashMap<String, CacheData> typeCacheMap = new HashMap<String, CacheData>();

    public LuaDocUnionTyImpl(@NotNull ASTNode node) {
        super(node);
    }

    @Override
    public void accept(@NotNull LuaDocVisitor visitor) {
        visitor.visitUnionTy(this);
    }

    @Override
    public void accept(@NotNull PsiElementVisitor visitor) {
        if (visitor instanceof LuaDocVisitor) accept((LuaDocVisitor) visitor);
        else super.accept(visitor);
    }

    @Override
    @NotNull
    public List<LuaDocTy> getTyList() {
        return PsiTreeUtil.getChildrenOfTypeAsList(this, LuaDocTy.class);
    }

    @Override
    @NotNull
    public ITy getType() {
        String text = getText();
        long now = System.currentTimeMillis();
        boolean useGlobalCache = LuaSettings.Companion.getInstance().isUseGlobalCache();
        if (useGlobalCache) {
            CacheData cacheData = typeCacheMap.get(text);
            if (cacheData != null && now - cacheData.cacheTime < 10000) {
                return cacheData.cacheTy;
            }
        }
        ITy type = LuaDocPsiImplUtilKt.getType(this);
        if (useGlobalCache) {
            CacheData cacheData = new CacheData();
            cacheData.cacheTime = now;
            cacheData.cacheTy = type;
            typeCacheMap.put(text, cacheData);
        }
        return type;
    }

    private static class CacheData {
        public long cacheTime = 0l;
        public ITy cacheTy = null;
    }
}
