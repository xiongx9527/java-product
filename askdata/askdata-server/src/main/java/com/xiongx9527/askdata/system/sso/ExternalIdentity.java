package com.xiongx9527.askdata.system.sso;

import java.util.Map;

/** IdP 回调解析出的外部身份（A7）。 */
public class ExternalIdentity {

    private final String account;
    private final String name;
    private final String email;
    private final String externalId;
    private final Map<String, Object> attributes;

    public ExternalIdentity(String account, String name, String email, String externalId,
                           Map<String, Object> attributes) {
        this.account = account;
        this.name = name;
        this.email = email;
        this.externalId = externalId;
        this.attributes = attributes;
    }

    public String getAccount() {
        return account;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getExternalId() {
        return externalId;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }
}
