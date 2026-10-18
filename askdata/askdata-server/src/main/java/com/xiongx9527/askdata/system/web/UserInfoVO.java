package com.xiongx9527.askdata.system.web;

/**
 * /user/info 响应体，字段与 SQLBot UserInfoDTO 逐字对齐：
 * 前端 user store 读取 id(→uid)/account/name/oid/language/exp/time/weight/origin。
 */
public class UserInfoVO {

    private long id;

    private String account;

    private String name;

    private String email;

    private long oid;

    private String language = "zh-CN";

    private long status;

    private long origin;

    private int weight;

    private long exp;

    private long time;

    private boolean isAdmin;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public long getOid() {
        return oid;
    }

    public void setOid(long oid) {
        this.oid = oid;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public long getStatus() {
        return status;
    }

    public void setStatus(long status) {
        this.status = status;
    }

    public long getOrigin() {
        return origin;
    }

    public void setOrigin(long origin) {
        this.origin = origin;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public long getExp() {
        return exp;
    }

    public void setExp(long exp) {
        this.exp = exp;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public boolean getIsAdmin() {
        return isAdmin;
    }

    public void setIsAdmin(boolean isAdmin) {
        this.isAdmin = isAdmin;
    }
}
