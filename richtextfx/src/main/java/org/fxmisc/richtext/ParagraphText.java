package org.fxmisc.richtext;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableMap;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.IndexRange;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.StrokeLineCap;

import javafx.scene.shape.StrokeType;
import org.fxmisc.richtext.model.Paragraph;
import org.fxmisc.richtext.model.StyledSegment;
import org.reactfx.util.Tuple2;
import org.reactfx.util.Tuples;
import org.reactfx.value.Val;

/**
 * The class responsible for rendering the segments in an paragraph. It also renders additional RichTextFX-specific
 * CSS found in {@link TextExt} as well as the selection and caret shapes.
 *
 * @param <PS> paragraph style type
 * @param <SEG> segment type
 * @param <S> segment style type
 */
class ParagraphText<PS, SEG, S> extends TextFlowExt {

    private final ObservableSet<CaretNode> carets = FXCollections.observableSet(new HashSet<>(1));
    public final ObservableSet<CaretNode> caretsProperty() { return carets; }

    private final ObservableMap<Selection<PS, SEG, S>, SelectionPath> selections =
            FXCollections.observableMap(new HashMap<>(1));
    public final ObservableMap<Selection<PS, SEG, S>, SelectionPath> selectionsProperty() { return selections; }

    // FIXME: changing it currently has not effect, because
    // Text.impl_selectionFillProperty().set(newFill) doesn't work
    // properly for Text node inside a TextFlow (as of JDK8-b100).
    private final ObjectProperty<Paint> highlightTextFill = new SimpleObjectProperty<>(Color.WHITE);
    public ObjectProperty<Paint> highlightTextFillProperty() {
        return highlightTextFill;
    }

    private final Paragraph<PS, SEG, S> paragraph;

    private final CustomCssShapeHelper<Paint> backgroundShapeHelper;
    private final CustomCssShapeHelper<BorderAttributes> borderShapeHelper;
    private final CustomCssShapeHelper<UnderlineAttributes> underlineShapeHelper;

    // Note: order of children matters because later children cover up earlier children:
    // towards children's 0 index:
    //      background shapes
    //      selection shapes - always add to selectionShapeStartIndex
    //      border shapes
    //      text
    //      underline shapes
    //      caret shapes
    // towards getChildren().size() - 1 index
    private int selectionShapeStartIndex = 0;

    ParagraphText(Paragraph<PS, SEG, S> par, Function<StyledSegment<SEG, S>, Node> nodeFactory) {
        this.paragraph = par;

        getStyleClass().add("paragraph-text");

        Val<Double> leftInset = Val.map(insetsProperty(), Insets::getLeft);
        Val<Double> topInset = Val.map(insetsProperty(), Insets::getTop);

        ChangeListener<IndexRange> requestLayout1 = new SelectionRangeChangeListener<>(this);
        selections.addListener(new SelectionsSetListener<>(leftInset, requestLayout1, topInset, this));

        ChangeListener<Integer> requestLayout2 = new CaretPositionChangeListener<>(this);
        carets.addListener(new CaretsChangeListener<>(leftInset, requestLayout2, topInset, this));

        // XXX: see the note at highlightTextFill
//        highlightTextFill.addListener(new ChangeListener<Paint>() {
//            @Override
//            public void changed(ObservableValue<? extends Paint> observable,
//                    Paint oldFill, Paint newFill) {
//                for(PumpedUpText text: textNodes())
//                    text.impl_selectionFillProperty().set(newFill);
//            }
//        });

        // populate with text nodes
        par.getStyledSegments().stream().map(nodeFactory).forEach(n -> {
            if (n instanceof TextExt) {
                TextExt t = (TextExt) n;
                // XXX: binding selectionFill to textFill,
                // see the note at highlightTextFill
                JavaFXCompatibility.Text_selectionFillProperty(t).bind(t.fillProperty());
            }
            getChildren().add(n);
        });

        // set up custom css shape helpers
        UnaryOperator<Path> configurePath = shape -> {
            shape.setManaged(false);
            shape.layoutXProperty().bind(leftInset);
            shape.layoutYProperty().bind(topInset);
            return shape;
        };
        Supplier<Path> createBackgroundShape = () -> configurePath.apply(new BackgroundPath());
        Supplier<Path> createBorderShape = () -> configurePath.apply(new BorderPath());
        Supplier<Path> createUnderlineShape = () -> configurePath.apply(new UnderlinePath());

        Consumer<Collection<Path>> clearUnusedShapes = paths -> getChildren().removeAll(paths);
        Consumer<Path> addToBackground = path -> getChildren().add(0, path);
        Consumer<Path> addToBackgroundAndIncrementSelectionIndex = addToBackground
                .andThen(ignore -> selectionShapeStartIndex++);
        Consumer<Path> addToForeground = path -> getChildren().add(path);
        backgroundShapeHelper = new CustomCssShapeHelper<>(
                createBackgroundShape,
                (backgroundShape, tuple) -> {
                    backgroundShape.setStrokeWidth(0);
                    backgroundShape.setFill(tuple._1);
                    backgroundShape.getElements().setAll(getRangeShape(tuple._2));
                },
                addToBackgroundAndIncrementSelectionIndex,
                clearUnusedShapes
        );
        borderShapeHelper = new CustomCssShapeHelper<>(
                createBorderShape,
                (borderShape, tuple) -> {
                    BorderAttributes attributes = tuple._1;
                    borderShape.setStrokeWidth(attributes.width);
                    borderShape.setStroke(attributes.color);
                    if (attributes.type != null) {
                        borderShape.setStrokeType(attributes.type);
                    }
                    if (attributes.dashArray != null) {
                        borderShape.getStrokeDashArray().setAll(attributes.dashArray);
                    }
                    borderShape.getElements().setAll(getRangeShape(tuple._2));
                },
                addToBackground,
                clearUnusedShapes
        );
        underlineShapeHelper = new CustomCssShapeHelper<>(
                createUnderlineShape,
                (underlineShape, tuple) -> {
                    UnderlineAttributes attributes = tuple._1;
                    underlineShape.setStroke(attributes.color);
                    underlineShape.setStrokeWidth(attributes.width);
                    underlineShape.setStrokeLineCap(attributes.cap);
                    if (attributes.dashArray != null) {
                        underlineShape.getStrokeDashArray().setAll(attributes.dashArray);
                    }
                    underlineShape.getElements().setAll(getUnderlineShape(tuple._2));
                },
                addToForeground,
                clearUnusedShapes
        );
    }

    public Paragraph<PS, SEG, S> getParagraph() {
        return paragraph;
    }

    public <T extends Node & Caret> double getCaretOffsetX(T caret) {
        layout(); // ensure layout, is a no-op if not dirty
        checkWithinParagraph(caret);
        Bounds bounds = caret.getLayoutBounds();
        return (bounds.getMinX() + bounds.getMaxX()) / 2;
    }

    public <T extends Node & Caret> Bounds getCaretBounds(T caret) {
        layout(); // ensure layout, is a no-op if not dirty
        checkWithinParagraph(caret);
        return caret.getBoundsInParent();
    }

    public <T extends Node & Caret> Bounds getCaretBoundsOnScreen(T caret) {
        layout(); // ensure layout, is a no-op if not dirty
        checkWithinParagraph(caret);
        Bounds localBounds = caret.getBoundsInLocal();
        return caret.localToScreen(localBounds);
    }

    public Bounds getRangeBoundsOnScreen(int from, int to) {
        layout(); // ensure layout, is a no-op if not dirty
        PathElement[] rangeShape = getRangeShapeSafely(from, to);

        Path p = new Path();
        p.setManaged(false);
        p.setLayoutX(getInsets().getLeft());
        p.setLayoutY(getInsets().getTop());

        getChildren().add(p);

        p.getElements().setAll(rangeShape);
        Bounds localBounds = p.getBoundsInLocal();
        Bounds rangeBoundsOnScreen = p.localToScreen(localBounds);

        getChildren().remove(p);

        return rangeBoundsOnScreen;
    }

    public Optional<Bounds> getSelectionBoundsOnScreen(Selection<PS, SEG, S> selection) {
        if(selection.getLength() == 0) {
            return Optional.empty();
        } else {
            layout(); // ensure layout, is a no-op if not dirty
            SelectionPath selectionShape = selections.get(selection);
            checkWithinParagraph(selectionShape);
            Bounds localBounds = selectionShape.getBoundsInLocal();
            return Optional.ofNullable(selectionShape.localToScreen(localBounds));
        }
    }

    public int getCurrentLineStartPosition(Caret caret) {
        return getLineStartPosition(getClampedCaretPosition(caret));
    }

    public int getCurrentLineEndPosition(Caret caret) {
        return getLineEndPosition(getClampedCaretPosition(caret));
    }

    public int currentLineIndex(Caret caret) {
        return getLineOfCharacter(getClampedCaretPosition(caret));
    }

    public int currentLineIndex(int position) {
        return getLineOfCharacter(position);
    }

    private <T extends Node> void checkWithinParagraph(T shape) {
        if (shape.getParent() != this) {
            throw new IllegalArgumentException(String.format(
                    "This ParagraphText is not the parent of the given shape (%s):\nExpected: %s\nActual:   %s",
                    shape, this, shape.getParent()
            ));
        }
    }
    private int getClampedCaretPosition(Caret caret) {
        return Math.min(caret.getColumnPosition(), paragraph.length());
    }

    private void updateAllCaretShapes() {
        carets.forEach(this::updateSingleCaret);
    }

    private void updateSingleCaret(CaretNode caretNode) {
        PathElement[] shape = getCaretShape(getClampedCaretPosition(caretNode), true);
        caretNode.getElements().setAll(shape);
    }

    private void updateAllSelectionShapes() {
        selections.values().forEach(this::updateSingleSelection);
    }

    private void updateSingleSelection(SelectionPath path) {
        path.getElements().setAll(getRangeShapeSafely(path.rangeProperty().getValue()));
    }

    private PathElement[] getRangeShapeSafely(IndexRange range) {
        return getRangeShapeSafely(range.getStart(), range.getEnd());
    }

    /**
     * Gets the range shape for the given positions within the text, including the newline character, if range
     * defined by the start/end arguments include it.
     *
     * @param start the start position of the range shape
     * @param end the end position of the range shape. If {@code end == paragraph.length() + 1}, the newline character
     *            will be included in the selection by selecting the rest of the line
     */
    private PathElement[] getRangeShapeSafely(int start, int end) {
        PathElement[] shape;
        if (end <= paragraph.length()) {
            // selection w/o newline char
            shape = getRangeShape(start, end);
        } else {
            // Selection includes a newline character.
            if (paragraph.length() == 0) {
                // empty paragraph
                shape = createRectangle(0, 0, getWidth(), getHeight());
            } else if (start == paragraph.length()) {
                // selecting only the newline char

                // calculate the bounds of the last character
                shape = getRangeShape(start - 1, start);
                LineTo lineToTopRight = (LineTo) shape[shape.length - 4];
                shape = createRectangle(lineToTopRight.getX(), lineToTopRight.getY(), getWidth(), getHeight());
            } else {
                shape = getRangeShape(start, paragraph.length());
                // Since this might be a wrapped multi-line paragraph,
                // there may be multiple groups of (1 MoveTo, 3 LineTo objects) for each line:
                // MoveTo(topLeft), LineTo(topRight), LineTo(bottomRight), LineTo(bottomLeft)

                // We only need to adjust the top right and bottom right corners to extend to the
                // width/height of the line, simulating a full line selection.
                int length = shape.length;
                if ( length > 3 )  // Prevent IndexOutOfBoundsException accessing shape[] issue #689
                {
                    int bottomRightIndex = length - 3;
                    int topRightIndex = bottomRightIndex - 1;
                    LineTo lineToTopRight = (LineTo) shape[topRightIndex];
                    shape[topRightIndex] = new LineTo(getWidth(), lineToTopRight.getY());
                    shape[bottomRightIndex] = new LineTo(getWidth(), getHeight());
                }
            }
        }

        if (getLineCount() > 1) {
            // adjust right corners of wrapped lines
            boolean wrappedAtEndPos = (end > 0 && getLineOfCharacter(end) > getLineOfCharacter(end - 1));
            int adjustLength = shape.length - (wrappedAtEndPos ? 0 : 5);
            for (int i = 0; i < adjustLength; i++) {
                if (shape[i] instanceof MoveTo) {
                    ((LineTo)shape[i + 1]).setX(getWidth());
                    ((LineTo)shape[i + 2]).setX(getWidth());
                }
            }
        }

        return shape;
    }

    private PathElement[] createRectangle(double topLeftX, double topLeftY, double bottomRightX, double bottomRightY) {
        return new PathElement[] {
                new MoveTo(topLeftX, topLeftY),
                new LineTo(bottomRightX, topLeftY),
                new LineTo(bottomRightX, bottomRightY),
                new LineTo(topLeftX, bottomRightY),
                new LineTo(topLeftX, topLeftY)
        };
    }

    private void updateBackgroundShapes() {
        int start = 0;

        // calculate shared values among consecutive nodes
        FilteredList<Node> nodeList = getChildren().filtered(node -> node instanceof TextExt);
        for (Node node : nodeList) {
            TextExt text = (TextExt) node;
            int end = start + text.getText().length();

            Paint backgroundColor = text.getBackgroundColor();
            if (backgroundColor != null) {
                backgroundShapeHelper.updateSharedShapeRange(backgroundColor, start, end, Paint::equals);
            }

            BorderAttributes border = new BorderAttributes(text);
            if (!border.isNullValue()) {
                borderShapeHelper.updateSharedShapeRange(border, start, end, BorderAttributes::equalsFaster);
            }

            UnderlineAttributes underline = new UnderlineAttributes(text);
            if (!underline.isNullValue()) {
                underlineShapeHelper.updateSharedShapeRange(underline, start, end, UnderlineAttributes::equalsFaster);
            }

            start = end;
        }

        borderShapeHelper.updateSharedShapes();
        backgroundShapeHelper.updateSharedShapes();
        underlineShapeHelper.updateSharedShapes();
    }

    @Override
    public String toString() {
        return String.format("ParagraphText@%s(paragraph=%s)", hashCode(), paragraph);
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        updateAllCaretShapes();
        updateAllSelectionShapes();
        updateBackgroundShapes();
    }

    private static final class SelectionsSetListener<PS, SEG, S> implements
                MapChangeListener<Selection<PS, SEG, S>, SelectionPath> {
        private final Val<Double> leftInset;
        private final ChangeListener<IndexRange> requestLayout1;
        private final Val<Double> topInset;
        private final WeakReference<ParagraphText<PS, SEG, S>> ref;

        public SelectionsSetListener(
                    Val<Double> leftInset,
                    ChangeListener<IndexRange> requestLayout1,
                    Val<Double> topInset,
                    ParagraphText<PS, SEG, S> paragraphText) {
            this.leftInset = leftInset;
            this.requestLayout1 = requestLayout1;
            this.topInset = topInset;
            ref = new WeakReference<>(paragraphText);
        }

        @Override
        public void onChanged(
                    javafx.collections.MapChangeListener.Change<? extends Selection<PS, SEG, S>, ? extends SelectionPath> change) {
            ParagraphText<PS, SEG, S> paragraphText = ref.get();
            if (null == paragraphText) {
                change.getMap().removeListener(this);
                return;
            }

            if (change.wasAdded()) {
                SelectionPath p = change.getValueAdded();
                p.rangeProperty().addListener(requestLayout1);

                p.layoutXProperty().bind(leftInset);
                p.layoutYProperty().bind(topInset);

                paragraphText.getChildren().add(paragraphText.selectionShapeStartIndex, p);
                paragraphText.updateSingleSelection(p);
            } else if (change.wasRemoved()) {
                SelectionPath p = change.getValueRemoved();
                p.rangeProperty().removeListener(requestLayout1);

                p.layoutXProperty().unbind();
                p.layoutYProperty().unbind();

                paragraphText.getChildren().remove(p);
            }
        }
    }

    private static final class SelectionRangeChangeListener<PS, SEG, S> implements ChangeListener<IndexRange> {
        private final WeakReference<ParagraphText<PS, SEG, S>> ref;

        public SelectionRangeChangeListener(ParagraphText<PS, SEG, S> paragraphText) {
            ref = new WeakReference<>(paragraphText);
        }

        @Override
        public void changed(ObservableValue<? extends IndexRange> observable, IndexRange oldValue, IndexRange newValue) {
            if (null == ref.get()) {
                observable.removeListener(this);
            } else {
                ref.get().requestLayout();
            }
        }
    }

    private static final class CaretPositionChangeListener<PS, SEG, S> implements ChangeListener<Integer> {
        private final WeakReference<ParagraphText<PS, SEG, S>> ref;

        public CaretPositionChangeListener(ParagraphText<PS, SEG, S> paragraphText) {
            ref = new WeakReference<>(paragraphText);
        }

        @Override
        public void changed(ObservableValue<? extends Integer> observable, Integer oldValue, Integer newValue) {
            if (null == ref.get()) {
                observable.removeListener(this);
            } else {
                ref.get().requestLayout();
            }
        }
    }

    private static final class CaretsChangeListener<PS, SEG, S> implements SetChangeListener<CaretNode> {
        private final Val<Double> leftInset;
        private final ChangeListener<Integer> requestLayout2;
        private final Val<Double> topInset;
        private final WeakReference<ParagraphText<PS, SEG, S>> ref;

        private CaretsChangeListener(
                    Val<Double> leftInset,
                    ChangeListener<Integer> requestLayout2,
                    Val<Double> topInset,
                    ParagraphText<PS, SEG, S> paragraphText) {
            ref = new WeakReference<>(paragraphText);
            this.leftInset = leftInset;
            this.requestLayout2 = requestLayout2;
            this.topInset = topInset;
        }

        @Override
        public void onChanged(Change<? extends CaretNode> change) {
            ParagraphText<PS, SEG, S> paragraphText = ref.get();
            if (null == paragraphText) {
                change.getSet().removeListener(this);
                return;
            }
            if (change.wasAdded()) {
                CaretNode caret = change.getElementAdded();
                caret.columnPositionProperty().addListener(requestLayout2);
                caret.layoutXProperty().bind(leftInset);
                caret.layoutYProperty().bind(topInset);

                paragraphText.getChildren().add(caret);
                paragraphText.updateSingleCaret(caret);
            } else if (change.wasRemoved()) {
                CaretNode caret = change.getElementRemoved();
                caret.columnPositionProperty().removeListener(requestLayout2);
                caret.layoutXProperty().unbind();
                caret.layoutYProperty().unbind();

                paragraphText.getChildren().remove(caret);
            }
        }
    }

    private static class CustomCssShapeHelper<T> {

        private final List<Tuple2<T, IndexRange>> ranges = new LinkedList<>();
        private final List<Path> shapes = new LinkedList<>();

        private final Supplier<Path> createShape;
        private final BiConsumer<Path, Tuple2<T, IndexRange>> configureShape;
        private final Consumer<Path> addToChildren;
        private final Consumer<Collection<Path>> clearUnusedShapes;

        CustomCssShapeHelper(Supplier<Path> createShape, BiConsumer<Path, Tuple2<T, IndexRange>> configureShape,
                             Consumer<Path> addToChildren, Consumer<Collection<Path>> clearUnusedShapes) {
            this.createShape = createShape;
            this.configureShape = configureShape;
            this.addToChildren = addToChildren;
            this.clearUnusedShapes = clearUnusedShapes;
        }

        /**
         * Calculates the range of a value (background color, underline, etc.) that is shared between multiple
         * consecutive {@link TextExt} nodes
         */
        private void updateSharedShapeRange(T value, int start, int end, BiFunction<T, T, Boolean> equals) {
            Runnable addNewValueRange = () -> ranges.add(Tuples.t(value, new IndexRange(start, end)));

            if (ranges.isEmpty()) {
                addNewValueRange.run();;
            } else {
                int lastIndex = ranges.size() - 1;
                Tuple2<T, IndexRange> lastShapeValueRange = ranges.get(lastIndex);
                T lastShapeValue = lastShapeValueRange._1;

                // calculate smallest possible position which is consecutive to the given start position
                final int prevEndNext = lastShapeValueRange.get2().getEnd();
                if (start == prevEndNext &&                // Consecutive?
                    equals.apply(lastShapeValue, value)) { // Same style?

                    IndexRange lastRange = lastShapeValueRange._2;
                    IndexRange extendedRange = new IndexRange(lastRange.getStart(), end);
                    ranges.set(lastIndex, Tuples.t(lastShapeValue, extendedRange));
                } else {
                    addNewValueRange.run();
                }
            }
        }

        /**
         * Updates the shapes calculated in {@link #updateSharedShapeRange(Object, int, int, BiFunction)} and
         * configures them via {@code configureShape}.
         */
        private void updateSharedShapes() {
            // remove or add shapes, depending on what's needed
            int neededNumber = ranges.size();
            int availableNumber = shapes.size();

            if (neededNumber < availableNumber) {
                List<Path> unusedShapes = shapes.subList(neededNumber, availableNumber);
                clearUnusedShapes.accept(unusedShapes);
                unusedShapes.clear();
            } else if (availableNumber < neededNumber) {
                for (int i = 0; i < neededNumber - availableNumber; i++) {
                    Path shape = createShape.get();

                    shapes.add(shape);
                    addToChildren.accept(shape);
                }
            }

            // update the shape's color and elements
            for (int i = 0; i < ranges.size(); i++) {
                configureShape.accept(shapes.get(i), ranges.get(i));
            }

            // clear, since it's no longer needed
            ranges.clear();
        }
    }

    private static class BorderAttributes extends LineAttributesBase {

        final StrokeType type;

        BorderAttributes(TextExt text) {
            super(text.getBorderStrokeColor(), text.getBorderStrokeWidth(), text.borderStrokeDashArrayProperty());
            type = text.getBorderStrokeType();
        }

        /**
         * Same as {@link #equals(Object)} but no need to check the object for its class
         */
        public boolean equalsFaster(BorderAttributes attr) {
            return super.equalsFaster(attr) && Objects.equals(type, attr.type);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof BorderAttributes) {
                BorderAttributes attributes = (BorderAttributes) obj;
                return equalsFaster(attributes);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return String.format("BorderAttributes[type=%s %s]", type, getSubString());
        }
    }

    private static class UnderlineAttributes extends LineAttributesBase {

        final StrokeLineCap cap;

        UnderlineAttributes(TextExt text) {
            super(text.getUnderlineColor(), text.getUnderlineWidth(), text.underlineDashArrayProperty());
            cap = text.getUnderlineCap();
        }

        /**
         * Same as {@link #equals(Object)} but no need to check the object for its class
         */
        public boolean equalsFaster(UnderlineAttributes attr) {
            return super.equalsFaster(attr) && Objects.equals(cap, attr.cap);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof UnderlineAttributes) {
                UnderlineAttributes attr = (UnderlineAttributes) obj;
                return equalsFaster(attr);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return String.format("UnderlineAttributes[cap=%s %s]", cap, getSubString());
        }
    }

    private static class LineAttributesBase {

        final double width;
        final Paint color;
        final Double[] dashArray;

        public final boolean isNullValue() { return color == null || width == -1; }

        /**
         * Java Quirk! Using {@code t.get[border/underline]DashArray()} throws a ClassCastException
         * "Double cannot be cast to Number". However, using {@code t.getDashArrayProperty().get()}
         * works without issue
         */
        LineAttributesBase(Paint color, Number width, ObjectProperty<Number[]> dashArrayProp) {
            this.color = color;
            if (color == null || width == null || width.doubleValue() <= 0) {
                // null value
                this.width = -1;
                dashArray = null;
            } else {
                // real value
                this.width = width.doubleValue();

                // get the dash array - JavaFX CSS parser seems to return either a Number[] array
                // or a single value, depending on whether only one or more than one value has been
                // specified in the CSS
                Object dashArrayProperty = dashArrayProp.get();
                if (dashArrayProperty != null) {
                    if (dashArrayProperty.getClass().isArray()) {
                        Number[] numberArray = (Number[]) dashArrayProperty;
                        dashArray = new Double[numberArray.length];
                        int idx = 0;
                        for (Number d : numberArray) {
                            dashArray[idx++] = (Double) d;
                        }
                    } else {
                        dashArray = new Double[1];
                        dashArray[0] = ((Double) dashArrayProperty).doubleValue();
                    }
                } else {
                    dashArray = null;
                }
            }
        }

        /**
         * Same as {@link #equals(Object)} but no need to check the object for its class
         */
        public boolean equalsFaster(LineAttributesBase attr) {
            return Objects.equals(width, attr.width)
                    && Objects.equals(color, attr.color)
                    && Arrays.equals(dashArray, attr.dashArray);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof LineAttributesBase) {
                LineAttributesBase attr = (LineAttributesBase) obj;
                return equalsFaster(attr);
            } else {
                return false;
            }
        }

        protected final String getSubString() {
            return String.format("width=%s color=%s dashArray=%s", width, color, Arrays.toString(dashArray));
        }
    }
}
