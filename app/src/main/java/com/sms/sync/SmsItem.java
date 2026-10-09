package com.sms.sync;

/** 一条短信 */
public class SmsItem {
    public final int id;
    public final String address;
    public final String body;
    public final long date;
    public final int type;

    public SmsItem(int id, String address, String body, long date, int type) {
        this.id = id;
        this.address = address;
        this.body = body;
        this.date = date;
        this.type = type;
    }
}
