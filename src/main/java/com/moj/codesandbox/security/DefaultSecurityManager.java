package com.moj.codesandbox.security;

import java.security.Permission;

/**
 * 默认安全管理器
 * 默认是禁止所有权限
 */
public class DefaultSecurityManager extends SecurityManager{
    @Override
    public void checkPermission(Permission perm) {
        super.checkPermission(perm);
    }
}