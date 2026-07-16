package top.ellan.mahjong.table.core;

import java.util.Objects;

public final class DelimitedFingerprintBuilder {
    private final StringBuilder delegate;
    private boolean needsSeparator;

    private DelimitedFingerprintBuilder(int capacity) {
        this.delegate = new StringBuilder(capacity);
    }

    public static DelimitedFingerprintBuilder create(int capacity) {
        return new DelimitedFingerprintBuilder(capacity);
    }

    public DelimitedFingerprintBuilder field(Object value) {
        this.appendFieldSeparator();
        this.delegate.append(Objects.toString(value, ""));
        return this.finishField();
    }

    public DelimitedFingerprintBuilder field(boolean value) {
        this.appendFieldSeparator();
        this.delegate.append(value);
        return this.finishField();
    }

    public DelimitedFingerprintBuilder field(char value) {
        this.appendFieldSeparator();
        this.delegate.append(value);
        return this.finishField();
    }

    public DelimitedFingerprintBuilder field(int value) {
        this.appendFieldSeparator();
        this.delegate.append(value);
        return this.finishField();
    }

    public DelimitedFingerprintBuilder raw(Object value) {
        this.delegate.append(value);
        return this;
    }

    public DelimitedFingerprintBuilder entrySeparator() {
        this.delegate.append(';');
        this.needsSeparator = false;
        return this;
    }

    private void appendFieldSeparator() {
        if (this.needsSeparator) {
            this.delegate.append(':');
        }
    }

    private DelimitedFingerprintBuilder finishField() {
        this.needsSeparator = true;
        return this;
    }

    @Override
    public String toString() {
        return this.delegate.toString();
    }
}
