package android.graphics;

/**
 * Minimal JVM test stub for {@code android.graphics.Rect}. The surefire classpath excludes the
 * system-scope Android jars (see pom.xml {@code classpathDependencyExcludes}), so production code
 * touching {@code Rect} was untestable on the JVM — the gap that let the zero-area
 * {@code MODEL_LLM_TAP} dispatch ship (cmpm forensics A1). This stub reproduces the AOSP
 * {@code frameworks/base/graphics/java/android/graphics/Rect.java} semantics for exactly the
 * members the dispatch-geometry tests exercise; every predicate below matches the documented AOSP
 * behavior (half-open coordinates: {@code right}/{@code bottom} exclusive; an empty rect fails
 * {@code contains} and yields an empty {@code intersect} result).
 */
public class Rect {

    public int left;
    public int top;
    public int right;
    public int bottom;

    public Rect() {
    }

    public Rect(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public Rect(Rect r) {
        this(r.left, r.top, r.right, r.bottom);
    }

    public final boolean isEmpty() {
        return left >= right || top >= bottom;
    }

    public final int width() {
        return right - left;
    }

    public final int height() {
        return bottom - top;
    }

    public final float exactCenterX() {
        return (left + right) * 0.5f;
    }

    public final float exactCenterY() {
        return (top + bottom) * 0.5f;
    }

    public boolean contains(int x, int y) {
        return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom;
    }

    public boolean intersect(Rect r) {
        return intersect(r.left, r.top, r.right, r.bottom);
    }

    public boolean intersect(int left, int top, int right, int bottom) {
        if (this.left < right && left < this.right && this.top < bottom && top < this.bottom) {
            if (this.left < left) this.left = left;
            if (this.top < top) this.top = top;
            if (this.right > right) this.right = right;
            if (this.bottom > bottom) this.bottom = bottom;
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "Rect(" + left + ", " + top + " - " + right + ", " + bottom + ")";
    }
}
