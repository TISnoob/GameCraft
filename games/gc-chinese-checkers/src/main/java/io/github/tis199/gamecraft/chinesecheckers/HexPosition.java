package io.github.tis199.gamecraft.chinesecheckers;

public record HexPosition(int q, int r) {
    public int s() { return -q - r; }
    public HexPosition neighbor(int dq, int dr) { return new HexPosition(q + dq, r + dr); }
    public double worldX(double size) { return size * (Math.sqrt(3) * q + Math.sqrt(3) / 2 * r); }
    public double worldY(double size) { return size * (3.0 / 2 * r); }
}
