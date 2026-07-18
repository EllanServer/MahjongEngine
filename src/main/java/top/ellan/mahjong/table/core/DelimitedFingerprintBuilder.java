package top.ellan.mahjong.table.core;

public final class DelimitedFingerprintBuilder {
    private final StringBuilder delegate;
    private boolean needsSeparator;

    private DelimitedFingerprintBuilder(int capacity) {
        this.delegate = new StringBuilder(capacity);
    }

    public static DelimitedFingerprintBuilder create(int capacity) {
        return new DelimitedFingerprintBuilder(capacity);
    }

    public DelimitedFingerprintBuilder field(String value) {
        if (this.needsSeparator) {
            this.delegate.append(':');
        }
        if (value != null) {
            this.delegate.append(value);
        }
        this.needsSeparator = true;
        return this;
    }

    public DelimitedFingerprintBuilder field(Object value) {
        if (this.needsSeparator) {
            this.delegate.append(':');
        }
        if (value != null) {
            this.delegate.append(value);
        }
        this.needsSeparator = true;
        return this;
    }

    public DelimitedFingerprintBuilder field(boolean value) {
        if (this.needsSeparator) {
            this.delegate.append(':');
        }
        this.delegate.append(value);
        this.needsSeparator = true;
        return this;
    }

    public DelimitedFingerprintBuilder field(char value) {
        if (this.needsSeparator) {
            this.delegate.append(':');
        }
        this.delegate.append(value);
        this.needsSeparator = true;
        return this;
    }

    public DelimitedFingerprintBuilder field(int value) {
        if (this.needsSeparator) {
            this.delegate.append(':');
        }
        this.delegate.append(value);
        this.needsSeparator = true;
        return this;
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

    @Override
    public String toString() {
        return this.delegate.toString();
    }
}
