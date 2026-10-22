package com.xiongx9527.askdata.system.sso;

import java.util.Map;

/** IdP 回调入参（不同协议字段不同，按需取用）。 */
public class IdpCallback {

    private String code;
    private String state;
    private String ticket;
    private String samlResponse;
    private String raw;

    public static IdpCallback fromQuery(Map<String, String> params) {
        IdpCallback cb = new IdpCallback();
        cb.code = params.get("code");
        cb.state = params.get("state");
        cb.ticket = params.get("ticket");
        cb.samlResponse = params.get("SAMLResponse");
        if (cb.samlResponse == null) {
            cb.samlResponse = params.get("samlResponse");
        }
        cb.raw = String.valueOf(params);
        return cb;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getTicket() {
        return ticket;
    }

    public void setTicket(String ticket) {
        this.ticket = ticket;
    }

    public String getSamlResponse() {
        return samlResponse;
    }

    public void setSamlResponse(String samlResponse) {
        this.samlResponse = samlResponse;
    }

    public String getRaw() {
        return raw;
    }

    public void setRaw(String raw) {
        this.raw = raw;
    }
}
