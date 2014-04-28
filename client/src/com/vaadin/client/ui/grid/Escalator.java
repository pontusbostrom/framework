/*
 * Copyright 2000-2013 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.client.ui.grid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gwt.animation.client.AnimationScheduler;
import com.google.gwt.animation.client.AnimationScheduler.AnimationCallback;
import com.google.gwt.animation.client.AnimationScheduler.AnimationHandle;
import com.google.gwt.core.client.Duration;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.Node;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.dom.client.Style;
import com.google.gwt.dom.client.Style.Display;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.logging.client.LogConfiguration;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.UIObject;
import com.google.gwt.user.client.ui.Widget;
import com.vaadin.client.Profiler;
import com.vaadin.client.Util;
import com.vaadin.client.ui.grid.Escalator.JsniUtil.TouchHandlerBundle;
import com.vaadin.client.ui.grid.PositionFunction.AbsolutePosition;
import com.vaadin.client.ui.grid.PositionFunction.Translate3DPosition;
import com.vaadin.client.ui.grid.PositionFunction.TranslatePosition;
import com.vaadin.client.ui.grid.PositionFunction.WebkitTranslate3DPosition;
import com.vaadin.client.ui.grid.ScrollbarBundle.HorizontalScrollbarBundle;
import com.vaadin.client.ui.grid.ScrollbarBundle.VerticalScrollbarBundle;
import com.vaadin.shared.ui.grid.GridState;
import com.vaadin.shared.ui.grid.HeightMode;
import com.vaadin.shared.ui.grid.Range;
import com.vaadin.shared.ui.grid.ScrollDestination;
import com.vaadin.shared.util.SharedUtil;

/*-

 Maintenance Notes! Reading these might save your day.


 == Row Container Structure

 AbstractRowContainer
 |-- AbstractStaticRowContainer
 | |-- HeaderRowContainer
 | `-- FooterContainer
 `-- BodyRowContainer

 AbstractRowContainer is intended to contain all common logic
 between RowContainers. It manages the bookkeeping of row
 count, makes sure that all individual cells are rendered
 the same way, and so on.

 AbstractStaticRowContainer has some special logic that is
 required by all RowContainers that don't scroll (hence the
 word "static"). HeaderRowContainer and FooterRowContainer
 are pretty thin special cases of a StaticRowContainer
 (mostly relating to positioning of the root element).

 BodyRowContainer could also be split into an additional
 "AbstractScrollingRowContainer", but I felt that no more
 inner classes were needed. So it contains both logic
 required for making things scroll about, and equivalent
 special cases for layouting, as are found in
 Header/FooterRowContainers.


 == The Three Indices

 Each RowContainer can be thought to have three levels of
 indices for any given displayed row (but the distinction
 matters primarily for the BodyRowContainer, because of the
 way it scrolls through data):

 - Logical index
 - Physical (or DOM) index
 - Visual index

 LOGICAL INDEX is the index that is linked to the data
 source. If you want your data source to represent a SQL
 database with 10 000 rows, the 7 000:th row in the SQL has a
 logical index of 6 999, since the index is 0-based (unless
 that data source does some funky logic).

 PHYSICAL INDEX is the index for a row that you see in a
 browser's DOM inspector. If your row is the second <tr>
 element within a <tbody> tag, it has a physical index of 1
 (because of 0-based indices). In Header and
 FooterRowContainers, you are safe to assume that the logical
 index is the same as the physical index. But because the
 BodyRowContainer never displays large data sources entirely
 in the DOM, a physical index usually has no apparent direct
 relationship with its logical index.

 VISUAL INDEX is the index relating to the order that you
 see a row in, in the browser, as it is rendered. The
 topmost row is 0, the second is 1, and so on. The visual
 index is similar to the physical index in the sense that
 Header and FooterRowContainers can assume a 1:1
 relationship between visual index and logical index. And
 again, BodyRowContainer has no such relationship. The
 body's visual index has additionally no apparent
 relationship with its physical index. Because the <tr> tags
 are reused in the body and visually repositioned with CSS
 as the user scrolls, the relationship between physical
 index and visual index is quickly broken. You can get an
 element's visual index via the field
 BodyRowContainer.visualRowOrder.

 */

/**
 * A workaround-class for GWT and JSNI.
 * <p>
 * GWT is unable to handle some method calls to Java methods in inner-classes
 * from within JSNI blocks. Having that inner class implement a non-inner-class
 * (or interface), makes it possible for JSNI to indirectly refer to the inner
 * class, by invoking methods and fields in the non-inner-class.
 * 
 * @see Escalator.Scroller
 */
abstract class JsniWorkaround {
    /**
     * A JavaScript function that handles the scroll DOM event, and passes it on
     * to Java code.
     * 
     * @see #createScrollListenerFunction(Escalator)
     * @see Escalator#onScroll()
     * @see Escalator.Scroller#onScroll()
     */
    protected final JavaScriptObject scrollListenerFunction;

    /**
     * A JavaScript function that handles the mousewheel DOM event, and passes
     * it on to Java code.
     * 
     * @see #createMousewheelListenerFunction(Escalator)
     * @see Escalator#onScroll()
     * @see Escalator.Scroller#onScroll()
     */
    protected final JavaScriptObject mousewheelListenerFunction;

    /**
     * A JavaScript function that handles the touch start DOM event, and passes
     * it on to Java code.
     * 
     * @see TouchHandlerBundle#touchStart(Escalator.JsniUtil.TouchHandlerBundle.CustomTouchEvent)
     */
    protected JavaScriptObject touchStartFunction;

    /**
     * A JavaScript function that handles the touch move DOM event, and passes
     * it on to Java code.
     * 
     * @see TouchHandlerBundle#touchMove(Escalator.JsniUtil.TouchHandlerBundle.CustomTouchEvent)
     */
    protected JavaScriptObject touchMoveFunction;

    /**
     * A JavaScript function that handles the touch end and cancel DOM events,
     * and passes them on to Java code.
     * 
     * @see TouchHandlerBundle#touchEnd(Escalator.JsniUtil.TouchHandlerBundle.CustomTouchEvent)
     */
    protected JavaScriptObject touchEndFunction;

    protected JsniWorkaround(final Escalator escalator) {
        scrollListenerFunction = createScrollListenerFunction(escalator);
        mousewheelListenerFunction = createMousewheelListenerFunction(escalator);

        final TouchHandlerBundle bundle = new TouchHandlerBundle(escalator);
        touchStartFunction = bundle.getTouchStartHandler();
        touchMoveFunction = bundle.getTouchMoveHandler();
        touchEndFunction = bundle.getTouchEndHandler();
    }

    /**
     * A method that constructs the JavaScript function that will be stored into
     * {@link #scrollListenerFunction}.
     * 
     * @param esc
     *            a reference to the current instance of {@link Escalator}
     * @see Escalator#onScroll()
     */
    protected abstract JavaScriptObject createScrollListenerFunction(
            Escalator esc);

    /**
     * A method that constructs the JavaScript function that will be stored into
     * {@link #mousewheelListenerFunction}.
     * 
     * @param esc
     *            a reference to the current instance of {@link Escalator}
     * @see Escalator#onScroll()
     */
    protected abstract JavaScriptObject createMousewheelListenerFunction(
            Escalator esc);
}

/**
 * A low-level table-like widget that features a scrolling virtual viewport and
 * lazily generated rows.
 * 
 * @since 7.2
 * @author Vaadin Ltd
 */
public class Escalator extends Widget {

    // todo comments legend
    /*
     * [[optimize]]: There's an opportunity to rewrite the code in such a way
     * that it _might_ perform better (rememeber to measure, implement,
     * re-measure)
     */
    /*
     * [[rowheight]]: This code will require alterations that are relevant for
     * being able to support variable row heights. NOTE: these bits can most
     * often also be identified by searching for code reading the ROW_HEIGHT_PX
     * constant.
     */
    /*
     * [[API]]: Implementing this suggestion would require a change in the
     * public API. These suggestions usually don't come lightly.
     */
    /*
     * [[mpixscroll]]: This code will require alterations that are relevant for
     * supporting the scrolling through more pixels than some browsers normally
     * would support. (i.e. when we support more than "a million" pixels in the
     * escalator DOM). NOTE: these bits can most often also be identified by
     * searching for code that call scrollElem.getScrollTop();.
     */

    /**
     * A utility class that contains utility methods that are usually called
     * from JSNI.
     * <p>
     * The methods are moved in this class to minimize the amount of JSNI code
     * as much as feasible.
     */
    static class JsniUtil {
        public static class TouchHandlerBundle {

            /**
             * A <a href=
             * "http://www.gwtproject.org/doc/latest/DevGuideCodingBasicsOverlay.html"
             * >JavaScriptObject overlay</a> for the <a
             * href="http://www.w3.org/TR/touch-events/">JavaScript
             * TouchEvent</a> object.
             * <p>
             * This needs to be used in the touch event handlers, since GWT's
             * {@link com.google.gwt.event.dom.client.TouchEvent TouchEvent}
             * can't be cast from the JSNI call, and the
             * {@link com.google.gwt.dom.client.NativeEvent NativeEvent} isn't
             * properly populated with the correct values.
             */
            private final static class CustomTouchEvent extends
                    JavaScriptObject {
                protected CustomTouchEvent() {
                }

                public native NativeEvent getNativeEvent()
                /*-{
                    return this;
                }-*/;

                public native int getPageX()
                /*-{
                    return this.targetTouches[0].pageX;
                }-*/;

                public native int getPageY()
                /*-{
                    return this.targetTouches[0].pageY;
                }-*/;
            }

            private double touches = 0;
            private int lastX = 0;
            private int lastY = 0;
            private double lastTime = 0;
            private boolean snappedScrollEnabled = true;
            private double deltaX = 0;
            private double deltaY = 0;

            private final Escalator escalator;
            private CustomTouchEvent latestTouchMoveEvent;
            private AnimationCallback mover = new AnimationCallback() {
                @Override
                public void execute(double doNotUseThisTimestamp) {
                    /*
                     * We can't use the timestamp parameter here, since it is
                     * not in any predetermined format; TouchEnd does not
                     * provide a compatible timestamp, and we need to be able to
                     * get a comparable timestamp to determine whether to
                     * trigger a flick scroll or not.
                     */

                    if (touches != 1) {
                        return;
                    }

                    final int x = latestTouchMoveEvent.getPageX();
                    final int y = latestTouchMoveEvent.getPageY();
                    deltaX = x - lastX;
                    deltaY = y - lastY;
                    lastX = x;
                    lastY = y;

                    /*
                     * Instead of using the provided arbitrary timestamp, let's
                     * use a known-format and reproducible timestamp.
                     */
                    lastTime = Duration.currentTimeMillis();

                    // snap the scroll to the major axes, at first.
                    if (snappedScrollEnabled) {
                        final double oldDeltaX = deltaX;
                        final double oldDeltaY = deltaY;

                        /*
                         * Scrolling snaps to 40 degrees vs. flick scroll's 30
                         * degrees, since slow movements have poor resolution -
                         * it's easy to interpret a slight angle as a steep
                         * angle, since the sample rate is "unnecessarily" high.
                         * 40 simply felt better than 30.
                         */
                        final double[] snapped = Escalator.snapDeltas(deltaX,
                                deltaY, RATIO_OF_40_DEGREES);
                        deltaX = snapped[0];
                        deltaY = snapped[1];

                        /*
                         * if the snap failed once, let's follow the pointer
                         * from now on.
                         */
                        if (oldDeltaX != 0 && deltaX == oldDeltaX
                                && oldDeltaY != 0 && deltaY == oldDeltaY) {
                            snappedScrollEnabled = false;
                        }
                    }

                    moveScrollFromEvent(escalator, -deltaX, -deltaY,
                            latestTouchMoveEvent.getNativeEvent());
                }
            };
            private AnimationHandle animationHandle;

            public TouchHandlerBundle(final Escalator escalator) {
                this.escalator = escalator;
            }

            public native JavaScriptObject getTouchStartHandler()
            /*-{
                // we need to store "this", since it won't be preserved on call.
                var self = this;
                return $entry(function (e) {
                    self.@com.vaadin.client.ui.grid.Escalator.JsniUtil.TouchHandlerBundle::touchStart(*)(e);
                });
            }-*/;

            public native JavaScriptObject getTouchMoveHandler()
            /*-{
                // we need to store "this", since it won't be preserved on call.
                var self = this;
                return $entry(function (e) {
                    self.@com.vaadin.client.ui.grid.Escalator.JsniUtil.TouchHandlerBundle::touchMove(*)(e);
                });
            }-*/;

            public native JavaScriptObject getTouchEndHandler()
            /*-{
                // we need to store "this", since it won't be preserved on call.
                var self = this;
                return $entry(function (e) {
                    self.@com.vaadin.client.ui.grid.Escalator.JsniUtil.TouchHandlerBundle::touchEnd(*)(e);
                });
            }-*/;

            public void touchStart(final CustomTouchEvent event) {
                touches++;
                if (touches != 1) {
                    return;
                }

                escalator.scroller.cancelFlickScroll();

                lastX = event.getPageX();
                lastY = event.getPageY();

                snappedScrollEnabled = true;
            }

            public void touchMove(final CustomTouchEvent event) {
                /*
                 * since we only use the getPageX/Y, and calculate the diff
                 * within the handler, we don't need to calculate any
                 * intermediate deltas.
                 */
                latestTouchMoveEvent = event;

                if (animationHandle != null) {
                    animationHandle.cancel();
                }
                animationHandle = AnimationScheduler.get()
                        .requestAnimationFrame(mover, escalator.bodyElem);
                event.getNativeEvent().preventDefault();
            }

            public void touchEnd(@SuppressWarnings("unused")
            final CustomTouchEvent event) {
                touches--;

                if (touches == 0) {
                    escalator.scroller.handleFlickScroll(deltaX, deltaY,
                            lastTime);
                }
            }
        }

        public static void moveScrollFromEvent(final Escalator escalator,
                final double deltaX, final double deltaY,
                final NativeEvent event) {

            if (!Double.isNaN(deltaX)) {
                escalator.horizontalScrollbar.setScrollPosByDelta(deltaX);
            }

            if (!Double.isNaN(deltaY)) {
                escalator.verticalScrollbar.setScrollPosByDelta(deltaY);
            }

            /*
             * TODO: only prevent if not scrolled to end/bottom. Or no? UX team
             * needs to decide.
             */
            final boolean warrantedYScroll = deltaY != 0
                    && escalator.verticalScrollbar.showsScrollHandle();
            final boolean warrantedXScroll = deltaX != 0
                    && escalator.horizontalScrollbar.showsScrollHandle();
            if (warrantedYScroll || warrantedXScroll) {
                event.preventDefault();
            }
        }
    }

    /**
     * The animation callback that handles the animation of a touch-scrolling
     * flick with inertia.
     */
    private class FlickScrollAnimator implements AnimationCallback {
        private static final double MIN_MAGNITUDE = 0.005;
        private static final double MAX_SPEED = 7;

        private double velX;
        private double velY;
        private double prevTime = 0;
        private int millisLeft;
        private double xFric;
        private double yFric;

        private boolean cancelled = false;
        private double lastLeft;
        private double lastTop;

        /**
         * Creates a new animation callback to handle touch-scrolling flick with
         * inertia.
         * 
         * @param deltaX
         *            the last scrolling delta in the x-axis in a touchmove
         * @param deltaY
         *            the last scrolling delta in the y-axis in a touchmove
         * @param lastTime
         *            the timestamp of the last touchmove
         */
        public FlickScrollAnimator(final double deltaX, final double deltaY,
                final double lastTime) {
            final double currentTimeMillis = Duration.currentTimeMillis();
            velX = Math.max(Math.min(deltaX / (currentTimeMillis - lastTime),
                    MAX_SPEED), -MAX_SPEED);
            velY = Math.max(Math.min(deltaY / (currentTimeMillis - lastTime),
                    MAX_SPEED), -MAX_SPEED);

            lastLeft = horizontalScrollbar.getScrollPos();
            lastTop = verticalScrollbar.getScrollPos();

            /*
             * If we're scrolling mainly in one of the four major directions,
             * and only a teeny bit to any other side, snap the scroll to that
             * major direction instead.
             */
            final double[] snapDeltas = Escalator.snapDeltas(velX, velY,
                    RATIO_OF_30_DEGREES);
            velX = snapDeltas[0];
            velY = snapDeltas[1];

            if (velX * velX + velY * velY > MIN_MAGNITUDE) {
                millisLeft = 1500;
                xFric = velX / millisLeft;
                yFric = velY / millisLeft;
            } else {
                millisLeft = 0;
            }

        }

        @Override
        public void execute(final double doNotUseThisTimestamp) {
            /*
             * We cannot use the timestamp provided to this method since it is
             * of a format that cannot be determined at will. Therefore, we need
             * a timestamp format that we can handle, so our calculations are
             * correct.
             */

            if (millisLeft <= 0 || cancelled) {
                scroller.currentFlickScroller = null;
                return;
            }

            final double timestamp = Duration.currentTimeMillis();
            if (prevTime == 0) {
                prevTime = timestamp;
                AnimationScheduler.get().requestAnimationFrame(this);
                return;
            }

            double currentLeft = horizontalScrollbar.getScrollPos();
            double currentTop = verticalScrollbar.getScrollPos();

            final double timeDiff = timestamp - prevTime;
            double left = currentLeft - velX * timeDiff;
            setScrollLeft(left);
            velX -= xFric * timeDiff;

            double top = currentTop - velY * timeDiff;
            setScrollTop(top);
            velY -= yFric * timeDiff;

            cancelBecauseOfEdgeOrCornerMaybe();

            prevTime = timestamp;
            millisLeft -= timeDiff;
            lastLeft = currentLeft;
            lastTop = currentTop;
            AnimationScheduler.get().requestAnimationFrame(this);
        }

        private void cancelBecauseOfEdgeOrCornerMaybe() {
            if (lastLeft == horizontalScrollbar.getScrollPos()
                    && lastTop == verticalScrollbar.getScrollPos()) {
                cancel();
            }
        }

        public void cancel() {
            cancelled = true;
        }
    }

    /**
     * ScrollDestination case-specific handling logic.
     */
    private static double getScrollPos(final ScrollDestination destination,
            final double targetStartPx, final double targetEndPx,
            final double viewportStartPx, final double viewportEndPx,
            final int padding) {

        final double viewportLength = viewportEndPx - viewportStartPx;

        switch (destination) {

        /*
         * Scroll as little as possible to show the target element. If the
         * element fits into view, this works as START or END depending on the
         * current scroll position. If the element does not fit into view, this
         * works as START.
         */
        case ANY: {
            final double startScrollPos = targetStartPx - padding;
            final double endScrollPos = targetEndPx + padding - viewportLength;

            if (startScrollPos < viewportStartPx) {
                return startScrollPos;
            } else if (targetEndPx + padding > viewportEndPx) {
                return endScrollPos;
            } else {
                // NOOP, it's already visible
                return viewportStartPx;
            }
        }

        /*
         * Scrolls so that the element is shown at the end of the viewport. The
         * viewport will, however, not scroll before its first element.
         */
        case END: {
            return targetEndPx + padding - viewportLength;
        }

        /*
         * Scrolls so that the element is shown in the middle of the viewport.
         * The viewport will, however, not scroll beyond its contents, given
         * more elements than what the viewport is able to show at once. Under
         * no circumstances will the viewport scroll before its first element.
         */
        case MIDDLE: {
            final double targetMiddle = targetStartPx
                    + (targetEndPx - targetStartPx) / 2;
            return targetMiddle - viewportLength / 2;
        }

        /*
         * Scrolls so that the element is shown at the start of the viewport.
         * The viewport will, however, not scroll beyond its contents.
         */
        case START: {
            return targetStartPx - padding;
        }

        /*
         * Throw an error if we're here. This can only mean that
         * ScrollDestination has been carelessly amended..
         */
        default: {
            throw new IllegalArgumentException(
                    "Internal: ScrollDestination has been modified, "
                            + "but Escalator.getScrollPos has not been updated "
                            + "to match new values.");
        }
        }

    }

    /** An inner class that handles all logic related to scrolling. */
    private class Scroller extends JsniWorkaround {
        private double lastScrollTop = 0;
        private double lastScrollLeft = 0;
        /**
         * The current flick scroll animator. This is <code>null</code> if the
         * view isn't animating a flick scroll at the moment.
         */
        private FlickScrollAnimator currentFlickScroller;

        public Scroller() {
            super(Escalator.this);
        }

        @Override
        protected native JavaScriptObject createScrollListenerFunction(
                Escalator esc)
        /*-{
            var vScroll = esc.@com.vaadin.client.ui.grid.Escalator::verticalScrollbar;
            var vScrollElem = vScroll.@com.vaadin.client.ui.grid.ScrollbarBundle::getElement()();

            var hScroll = esc.@com.vaadin.client.ui.grid.Escalator::horizontalScrollbar;
            var hScrollElem = hScroll.@com.vaadin.client.ui.grid.ScrollbarBundle::getElement()();

            return $entry(function(e) {
                var target = e.target || e.srcElement; // IE8 uses e.scrElement
            
                // in case the scroll event was native (i.e. scrollbars were dragged, or
                // the scrollTop/Left was manually modified), the bundles have old cache
                // values. We need to make sure that the caches are kept up to date.
                if (target === vScrollElem) {
                    vScroll.@com.vaadin.client.ui.grid.ScrollbarBundle::updateScrollPosFromDom()();
                } else if (target === hScrollElem) {
                    hScroll.@com.vaadin.client.ui.grid.ScrollbarBundle::updateScrollPosFromDom()();
                } else {
                    $wnd.console.error("unexpected scroll target: "+target);
                }

                esc.@com.vaadin.client.ui.grid.Escalator::onScroll()();
            });
        }-*/;

        @Override
        protected native JavaScriptObject createMousewheelListenerFunction(
                Escalator esc)
        /*-{
            return $entry(function(e) {
                var deltaX = e.deltaX ? e.deltaX : -0.5*e.wheelDeltaX;
                var deltaY = e.deltaY ? e.deltaY : -0.5*e.wheelDeltaY;
                
                // IE8 has only delta y
                if (isNaN(deltaY)) {
                    deltaY = -0.5*e.wheelDelta;
                }

                @com.vaadin.client.ui.grid.Escalator.JsniUtil::moveScrollFromEvent(*)(esc, deltaX, deltaY, e);
            });
        }-*/;

        /**
         * Recalculates the virtual viewport represented by the scrollbars, so
         * that the sizes of the scroll handles appear correct in the browser
         */
        public void recalculateScrollbarsForVirtualViewport() {
            int scrollContentHeight = body.calculateEstimatedTotalRowHeight();
            int scrollContentWidth = columnConfiguration.calculateRowWidth();

            double tableWrapperHeight = heightOfEscalator;
            double tableWrapperWidth = widthOfEscalator;

            boolean verticalScrollNeeded = scrollContentHeight > tableWrapperHeight
                    - header.heightOfSection - footer.heightOfSection;
            boolean horizontalScrollNeeded = scrollContentWidth > tableWrapperWidth;

            // One dimension got scrollbars, but not the other. Recheck time!
            if (verticalScrollNeeded != horizontalScrollNeeded) {
                if (!verticalScrollNeeded && horizontalScrollNeeded) {
                    verticalScrollNeeded = scrollContentHeight > tableWrapperHeight
                            - header.heightOfSection
                            - footer.heightOfSection
                            - horizontalScrollbar.getScrollbarThickness();
                } else {
                    horizontalScrollNeeded = scrollContentWidth > tableWrapperWidth
                            - verticalScrollbar.getScrollbarThickness();
                }
            }

            // let's fix the table wrapper size, since it's now stable.
            if (verticalScrollNeeded) {
                tableWrapperWidth -= verticalScrollbar.getScrollbarThickness();
            }
            if (horizontalScrollNeeded) {
                tableWrapperHeight -= horizontalScrollbar
                        .getScrollbarThickness();
            }
            tableWrapper.getStyle().setHeight(tableWrapperHeight, Unit.PX);
            tableWrapper.getStyle().setWidth(tableWrapperWidth, Unit.PX);

            verticalScrollbar.setOffsetSize(tableWrapperHeight
                    - footer.heightOfSection - header.heightOfSection);
            verticalScrollbar.setScrollSize(scrollContentHeight);

            /*
             * If decreasing the amount of frozen columns, and scrolled to the
             * right, the scroll position might reset. So we need to remember
             * the scroll position, and re-apply it once the scrollbar size has
             * been adjusted.
             */
            double prevScrollPos = horizontalScrollbar.getScrollPos();

            int unfrozenPixels = columnConfiguration
                    .getCalculatedColumnsWidth(Range.between(
                            columnConfiguration.getFrozenColumnCount(),
                            columnConfiguration.getColumnCount()));
            int frozenPixels = scrollContentWidth - unfrozenPixels;
            double hScrollOffsetWidth = tableWrapperWidth - frozenPixels;
            horizontalScrollbar.setOffsetSize(hScrollOffsetWidth);
            horizontalScrollbar.setScrollSize(unfrozenPixels);
            horizontalScrollbar.getElement().getStyle()
                    .setLeft(frozenPixels, Unit.PX);
            horizontalScrollbar.setScrollPos(prevScrollPos);
        }

        /**
         * Logical scrolling event handler for the entire widget.
         */
        public void onScroll() {
            if (internalScrollEventCalls > 0) {
                internalScrollEventCalls--;
                return;
            }

            final double scrollTop = verticalScrollbar.getScrollPos();
            final double scrollLeft = horizontalScrollbar.getScrollPos();
            if (lastScrollLeft != scrollLeft) {
                for (int i = 0; i < columnConfiguration.frozenColumns; i++) {
                    header.updateFreezePosition(i, scrollLeft);
                    body.updateFreezePosition(i, scrollLeft);
                    footer.updateFreezePosition(i, scrollLeft);
                }

                position.set(headElem, -scrollLeft, 0);

                /*
                 * TODO [[optimize]]: cache this value in case the instanceof
                 * check has undesirable overhead. This could also be a
                 * candidate for some deferred binding magic so that e.g.
                 * AbsolutePosition is not even considered in permutations that
                 * we know support something better. That would let the compiler
                 * completely remove the entire condition since it knows that
                 * the if will never be true.
                 */
                if (position instanceof AbsolutePosition) {
                    /*
                     * we don't want to put "top: 0" on the footer, since it'll
                     * render wrong, as we already have
                     * "bottom: $footer-height".
                     */
                    footElem.getStyle().setLeft(-scrollLeft, Unit.PX);
                } else {
                    position.set(footElem, -scrollLeft, 0);
                }

                lastScrollLeft = scrollLeft;
            }

            body.setBodyScrollPosition(scrollLeft, scrollTop);

            lastScrollTop = scrollTop;
            body.updateEscalatorRowsOnScroll();
            /*
             * TODO [[optimize]]: Might avoid a reflow by first calculating new
             * scrolltop and scrolleft, then doing the escalator magic based on
             * those numbers and only updating the positions after that.
             */
        }

        public native void attachScrollListener(Element element)
        /*
         * Attaching events with JSNI instead of the GWT event mechanism because
         * GWT didn't provide enough details in events, or triggering the event
         * handlers with GWT bindings was unsuccessful. Maybe, with more time
         * and skill, it could be done with better success. JavaScript overlay
         * types might work. This might also get rid of the JsniWorkaround
         * class.
         */
        /*-{
             if (element.addEventListener) {
                 element.addEventListener("scroll", this.@com.vaadin.client.ui.grid.JsniWorkaround::scrollListenerFunction);
             } else {
                 element.attachEvent("onscroll", this.@com.vaadin.client.ui.grid.JsniWorkaround::scrollListenerFunction);
             }
        }-*/;

        public native void detachScrollListener(Element element)
        /*
         * Attaching events with JSNI instead of the GWT event mechanism because
         * GWT didn't provide enough details in events, or triggering the event
         * handlers with GWT bindings was unsuccessful. Maybe, with more time
         * and skill, it could be done with better success. JavaScript overlay
         * types might work. This might also get rid of the JsniWorkaround
         * class.
         */
        /*-{
            if (element.addEventListener) {
                element.removeEventListener("scroll", this.@com.vaadin.client.ui.grid.JsniWorkaround::scrollListenerFunction);
            } else {
                element.detachEvent("onscroll", this.@com.vaadin.client.ui.grid.JsniWorkaround::scrollListenerFunction);
            }
        }-*/;

        public native void attachMousewheelListener(Element element)
        /*
         * Attaching events with JSNI instead of the GWT event mechanism because
         * GWT didn't provide enough details in events, or triggering the event
         * handlers with GWT bindings was unsuccessful. Maybe, with more time
         * and skill, it could be done with better success. JavaScript overlay
         * types might work. This might also get rid of the JsniWorkaround
         * class.
         */
        /*-{
            if (element.addEventListener) {
                // firefox likes "wheel", while others use "mousewheel"
                var eventName = element.onwheel===undefined?"mousewheel":"wheel";
                element.addEventListener(eventName, this.@com.vaadin.client.ui.grid.JsniWorkaround::mousewheelListenerFunction);
            } else {
                // IE8
                element.attachEvent("onmousewheel", this.@com.vaadin.client.ui.grid.JsniWorkaround::mousewheelListenerFunction);
            }
        }-*/;

        public native void detachMousewheelListener(Element element)
        /*
         * Detaching events with JSNI instead of the GWT event mechanism because
         * GWT didn't provide enough details in events, or triggering the event
         * handlers with GWT bindings was unsuccessful. Maybe, with more time
         * and skill, it could be done with better success. JavaScript overlay
         * types might work. This might also get rid of the JsniWorkaround
         * class.
         */
        /*-{
            if (element.addEventListener) {
                // firefox likes "wheel", while others use "mousewheel"
                var eventName = element.onwheel===undefined?"mousewheel":"wheel";
                element.removeEventListener(eventName, this.@com.vaadin.client.ui.grid.JsniWorkaround::mousewheelListenerFunction);
            } else {
                // IE8
                element.detachEvent("onmousewheel", this.@com.vaadin.client.ui.grid.JsniWorkaround::mousewheelListenerFunction);
            }
        }-*/;

        public native void attachTouchListeners(Element element)
        /*
         * Detaching events with JSNI instead of the GWT event mechanism because
         * GWT didn't provide enough details in events, or triggering the event
         * handlers with GWT bindings was unsuccessful. Maybe, with more time
         * and skill, it could be done with better success. JavaScript overlay
         * types might work. This might also get rid of the JsniWorkaround
         * class.
         */
        /*-{
            if (element.addEventListener) {
                element.addEventListener("touchstart", this.@com.vaadin.client.ui.grid.JsniWorkaround::touchStartFunction);
                element.addEventListener("touchmove", this.@com.vaadin.client.ui.grid.JsniWorkaround::touchMoveFunction);
                element.addEventListener("touchend", this.@com.vaadin.client.ui.grid.JsniWorkaround::touchEndFunction);
                element.addEventListener("touchcancel", this.@com.vaadin.client.ui.grid.JsniWorkaround::touchEndFunction);
            } else {
                // this would be IE8, but we don't support it with touch
            }
        }-*/;

        public native void detachTouchListeners(Element element)
        /*
         * Detaching events with JSNI instead of the GWT event mechanism because
         * GWT didn't provide enough details in events, or triggering the event
         * handlers with GWT bindings was unsuccessful. Maybe, with more time
         * and skill, it could be done with better success. JavaScript overlay
         * types might work. This might also get rid of the JsniWorkaround
         * class.
         */
        /*-{
            if (element.removeEventListener) {
                element.removeEventListener("touchstart", this.@com.vaadin.client.ui.grid.JsniWorkaround::touchStartFunction);
                element.removeEventListener("touchmove", this.@com.vaadin.client.ui.grid.JsniWorkaround::touchMoveFunction);
                element.removeEventListener("touchend", this.@com.vaadin.client.ui.grid.JsniWorkaround::touchEndFunction);
                element.removeEventListener("touchcancel", this.@com.vaadin.client.ui.grid.JsniWorkaround::touchEndFunction);
            } else {
                // this would be IE8, but we don't support it with touch
            }
        }-*/;

        private void cancelFlickScroll() {
            if (currentFlickScroller != null) {
                currentFlickScroller.cancel();
            }
        }

        /**
         * Handles a touch-based flick scroll.
         * 
         * @param deltaX
         *            the last scrolling delta in the x-axis in a touchmove
         * @param deltaY
         *            the last scrolling delta in the y-axis in a touchmove
         * @param lastTime
         *            the timestamp of the last touchmove
         */
        public void handleFlickScroll(double deltaX, double deltaY,
                double lastTime) {
            currentFlickScroller = new FlickScrollAnimator(deltaX, deltaY,
                    lastTime);
            AnimationScheduler.get()
                    .requestAnimationFrame(currentFlickScroller);
        }

        public void scrollToColumn(final int columnIndex,
                final ScrollDestination destination, final int padding) {
            assert columnIndex >= columnConfiguration.frozenColumns : "Can't scroll to a frozen column";

            /*
             * To cope with frozen columns, we just pretend those columns are
             * not there at all when calculating the position of the target
             * column and the boundaries of the viewport. The resulting
             * scrollLeft will be correct without compensation since the DOM
             * structure effectively means that scrollLeft also ignores the
             * frozen columns.
             */
            final int frozenPixels = columnConfiguration
                    .getCalculatedColumnsWidth(Range.withLength(0,
                            columnConfiguration.frozenColumns));

            final int targetStartPx = columnConfiguration
                    .getCalculatedColumnsWidth(Range.withLength(0, columnIndex))
                    - frozenPixels;
            final int targetEndPx = targetStartPx
                    + columnConfiguration.getColumnWidthActual(columnIndex);

            final double viewportStartPx = getScrollLeft();
            double viewportEndPx = viewportStartPx
                    + getElement().getOffsetWidth() - frozenPixels;
            if (verticalScrollbar.showsScrollHandle()) {
                viewportEndPx -= Util.getNativeScrollbarSize();
            }

            final double scrollLeft = getScrollPos(destination, targetStartPx,
                    targetEndPx, viewportStartPx, viewportEndPx, padding);

            /*
             * note that it doesn't matter if the scroll would go beyond the
             * content, since the browser will adjust for that, and everything
             * fall into line accordingly.
             */
            setScrollLeft(scrollLeft);
        }

        public void scrollToRow(final int rowIndex,
                final ScrollDestination destination, final int padding) {
            /*
             * FIXME [[rowheight]]: coded to work only with default row heights
             * - will not work with variable row heights
             */
            final int targetStartPx = body.getDefaultRowHeight() * rowIndex;
            final int targetEndPx = targetStartPx + body.getDefaultRowHeight();

            final double viewportStartPx = getScrollTop();
            final double viewportEndPx = viewportStartPx
                    + body.calculateHeight();

            final double scrollTop = getScrollPos(destination, targetStartPx,
                    targetEndPx, viewportStartPx, viewportEndPx, padding);

            /*
             * note that it doesn't matter if the scroll would go beyond the
             * content, since the browser will adjust for that, and everything
             * falls into line accordingly.
             */
            setScrollTop(scrollTop);
        }
    }

    private abstract class AbstractRowContainer implements RowContainer {

        private EscalatorUpdater updater = EscalatorUpdater.NULL;

        private int rows;

        /**
         * The table section element ({@code <thead>}, {@code <tbody>} or
         * {@code <tfoot>}) the rows (i.e. {@code <tr>} tags) are contained in.
         */
        protected final Element root;

        /** The height of the combined rows in the DOM. */
        protected double heightOfSection = -1;

        /**
         * The primary style name of the escalator. Most commonly provided by
         * Escalator as "v-escalator".
         */
        private String primaryStyleName = null;

        /**
         * A map containing cached values of an element's current top position.
         * <p>
         * Don't use this field directly, because it will not take proper care
         * of all the bookkeeping required.
         * 
         * @deprecated Use {@link #setRowPosition(Element, int, int)},
         *             {@link #getRowTop(Element)} and
         *             {@link #removeRowPosition(Element)} instead.
         */
        @Deprecated
        private final Map<Element, Integer> rowTopPositionMap = new HashMap<Element, Integer>();

        private boolean defaultRowHeightShouldBeAutodetected = true;

        private int defaultRowHeight = INITIAL_DEFAULT_ROW_HEIGHT;

        public AbstractRowContainer(final Element rowContainerElement) {
            root = rowContainerElement;
        }

        /**
         * Gets the tag name of an element to represent a cell in a row.
         * <p>
         * Usually {@code "th"} or {@code "td"}.
         * <p>
         * <em>Note:</em> To actually <em>create</em> such an element, use
         * {@link #createCellElement(int, int)} instead.
         * 
         * @return the tag name for the element to represent cells as
         * @see #createCellElement(int, int)
         */
        protected abstract String getCellElementTagName();

        @Override
        public EscalatorUpdater getEscalatorUpdater() {
            return updater;
        }

        /**
         * {@inheritDoc}
         * <p>
         * <em>Implementation detail:</em> This method does no DOM modifications
         * (i.e. is very cheap to call) if there is no data for rows or columns
         * when this method is called.
         * 
         * @see #hasColumnAndRowData()
         */
        @Override
        public void setEscalatorUpdater(final EscalatorUpdater escalatorUpdater) {
            if (escalatorUpdater == null) {
                throw new IllegalArgumentException(
                        "escalator updater cannot be null");
            }

            updater = escalatorUpdater;

            if (hasColumnAndRowData() && getRowCount() > 0) {
                refreshRows(0, getRowCount());
            }
        }

        /**
         * {@inheritDoc}
         * <p>
         * <em>Implementation detail:</em> This method does no DOM modifications
         * (i.e. is very cheap to call) if there are no rows in the DOM when
         * this method is called.
         * 
         * @see #hasSomethingInDom()
         */
        @Override
        public void removeRows(final int index, final int numberOfRows) {
            assertArgumentsAreValidAndWithinRange(index, numberOfRows);

            rows -= numberOfRows;

            if (!isAttached()) {
                return;
            }

            if (hasSomethingInDom()) {
                paintRemoveRows(index, numberOfRows);
            }
        }

        protected abstract void paintRemoveRows(final int index,
                final int numberOfRows);

        private void assertArgumentsAreValidAndWithinRange(final int index,
                final int numberOfRows) throws IllegalArgumentException,
                IndexOutOfBoundsException {
            if (numberOfRows < 1) {
                throw new IllegalArgumentException(
                        "Number of rows must be 1 or greater (was "
                                + numberOfRows + ")");
            }

            if (index < 0 || index + numberOfRows > getRowCount()) {
                throw new IndexOutOfBoundsException("The given "
                        + "row range (" + index + ".." + (index + numberOfRows)
                        + ") was outside of the current number of rows ("
                        + getRowCount() + ")");
            }
        }

        @Override
        public int getRowCount() {
            return rows;
        }

        /**
         * {@inheritDoc}
         * <p>
         * <em>Implementation detail:</em> This method does no DOM modifications
         * (i.e. is very cheap to call) if there is no data for columns when
         * this method is called.
         * 
         * @see #hasColumnAndRowData()
         */
        @Override
        public void insertRows(final int index, final int numberOfRows) {
            if (index < 0 || index > getRowCount()) {
                throw new IndexOutOfBoundsException("The given index (" + index
                        + ") was outside of the current number of rows (0.."
                        + getRowCount() + ")");
            }

            if (numberOfRows < 1) {
                throw new IllegalArgumentException(
                        "Number of rows must be 1 or greater (was "
                                + numberOfRows + ")");
            }

            rows += numberOfRows;

            /*
             * only add items in the DOM if the widget itself is attached to the
             * DOM. We can't calculate sizes otherwise.
             */
            if (isAttached()) {
                paintInsertRows(index, numberOfRows);
            }
        }

        /**
         * Actually add rows into the DOM, now that everything can be
         * calculated.
         * 
         * @param visualIndex
         *            the DOM index to add rows into
         * @param numberOfRows
         *            the number of rows to insert
         * @return a list of the added row elements
         */
        protected List<Element> paintInsertRows(final int visualIndex,
                final int numberOfRows) {
            assert isAttached() : "Can't paint rows if Escalator is not attached";

            final List<Element> addedRows = new ArrayList<Element>();

            if (numberOfRows < 1) {
                return addedRows;
            }

            Node referenceRow;
            if (root.getChildCount() != 0 && visualIndex != 0) {
                // get the row node we're inserting stuff after
                referenceRow = root.getChild(visualIndex - 1);
            } else {
                // index is 0, so just prepend.
                referenceRow = null;
            }

            for (int row = visualIndex; row < visualIndex + numberOfRows; row++) {
                final int rowHeight = getDefaultRowHeight();
                final Element tr = DOM.createTR();
                addedRows.add(tr);
                tr.addClassName(getStylePrimaryName() + "-row");
                referenceRow = insertAfterReferenceAndUpdateIt(root, tr,
                        referenceRow);

                for (int col = 0; col < columnConfiguration.getColumnCount(); col++) {
                    final int colWidth = columnConfiguration
                            .getColumnWidthActual(col);
                    final Element cellElem = createCellElement(rowHeight,
                            colWidth);
                    tr.appendChild(cellElem);

                    // Set stylename and position if new cell is frozen
                    if (col < columnConfiguration.frozenColumns) {
                        cellElem.addClassName("frozen");
                        position.set(cellElem, scroller.lastScrollLeft, 0);
                    }
                }

                refreshRow(tr, row);
            }
            reapplyRowWidths();

            recalculateSectionHeight();

            return addedRows;
        }

        private Node insertAfterReferenceAndUpdateIt(final Element parent,
                final Element elem, final Node referenceNode) {
            if (referenceNode != null) {
                parent.insertAfter(elem, referenceNode);
            } else {
                /*
                 * referencenode being null means we have offset 0, i.e. make it
                 * the first row
                 */
                /*
                 * TODO [[optimize]]: Is insertFirst or append faster for an
                 * empty root?
                 */
                parent.insertFirst(elem);
            }
            return elem;
        }

        abstract protected void recalculateSectionHeight();

        /**
         * Returns the estimated height of all rows in the row container.
         * <p>
         * The estimate is promised to be correct as long as there are no rows
         * with calculated heights.
         */
        protected int calculateEstimatedTotalRowHeight() {
            return getDefaultRowHeight() * getRowCount();
        }

        /**
         * {@inheritDoc}
         * <p>
         * <em>Implementation detail:</em> This method does no DOM modifications
         * (i.e. is very cheap to call) if there is no data for columns when
         * this method is called.
         * 
         * @see #hasColumnAndRowData()
         */
        @Override
        public void refreshRows(final int index, final int numberOfRows) {
            Profiler.enter("Escalator.AbstractRowContainer.refreshRows");

            assertArgumentsAreValidAndWithinRange(index, numberOfRows);

            if (!isAttached()) {
                return;
            }

            /*
             * TODO [[rowheight]]: even if no rows are evaluated in the current
             * viewport, the heights of some unrendered rows might change in a
             * refresh. This would cause the scrollbar to be adjusted (in
             * scrollHeight and/or scrollTop). Do we want to take this into
             * account?
             */
            if (hasColumnAndRowData()) {
                /*
                 * TODO [[rowheight]]: nudge rows down with
                 * refreshRowPositions() as needed
                 */
                for (int row = index; row < index + numberOfRows; row++) {
                    final Node tr = getTrByVisualIndex(row);
                    refreshRow(tr, row);
                }
            }

            Profiler.leave("Escalator.AbstractRowContainer.refreshRows");
        }

        void refreshRow(final Node tr, final int logicalRowIndex) {
            flyweightRow.setup((Element) tr, logicalRowIndex,
                    columnConfiguration.getCalculatedColumnWidths());
            updater.updateCells(flyweightRow, flyweightRow.getCells());

            /*
             * the "assert" guarantees that this code is run only during
             * development/debugging.
             */
            assert flyweightRow.teardown();
        }

        /**
         * Create and setup an empty cell element.
         * 
         * @param width
         *            the width of the cell, in pixels
         * @param height
         *            the height of the cell, in pixels
         * 
         * @return a set-up empty cell element
         */
        @SuppressWarnings("hiding")
        public Element createCellElement(final int height, final int width) {
            final Element cellElem = DOM.createElement(getCellElementTagName());
            cellElem.getStyle().setHeight(height, Unit.PX);
            cellElem.getStyle().setWidth(width, Unit.PX);
            cellElem.addClassName(getStylePrimaryName() + "-cell");
            return cellElem;
        }

        /**
         * Gets the child element that is visually at a certain index
         * 
         * @param index
         *            the index of the element to retrieve
         * @return the element at position {@code index}
         * @throws IndexOutOfBoundsException
         *             if {@code index} is not valid within {@link #root}
         */
        abstract protected Element getTrByVisualIndex(int index)
                throws IndexOutOfBoundsException;

        protected void paintRemoveColumns(final int offset,
                final int numberOfColumns,
                final List<ColumnConfigurationImpl.Column> removedColumns) {
            final NodeList<Node> childNodes = root.getChildNodes();
            for (int visualRowIndex = 0; visualRowIndex < childNodes
                    .getLength(); visualRowIndex++) {
                final Node tr = childNodes.getItem(visualRowIndex);

                for (int column = 0; column < numberOfColumns; column++) {
                    Element cellElement = tr.getChild(offset).cast();
                    detachPossibleWidgetFromCell(cellElement);
                    cellElement.removeFromParent();
                }
            }
            reapplyRowWidths();

            final int firstRemovedColumnLeft = columnConfiguration
                    .getCalculatedColumnsWidth(Range.withLength(0, offset));
            final boolean columnsWereRemovedFromLeftOfTheViewport = scroller.lastScrollLeft > firstRemovedColumnLeft;

            if (columnsWereRemovedFromLeftOfTheViewport) {
                int removedColumnsPxAmount = 0;
                for (ColumnConfigurationImpl.Column removedColumn : removedColumns) {
                    removedColumnsPxAmount += removedColumn
                            .getCalculatedWidth();
                }
                final int leftByDiff = (int) (scroller.lastScrollLeft - removedColumnsPxAmount);
                final int newScrollLeft = Math.max(firstRemovedColumnLeft,
                        leftByDiff);
                horizontalScrollbar.setScrollPos(newScrollLeft);
            }

            // this needs to be after the scroll position adjustment above.
            scroller.recalculateScrollbarsForVirtualViewport();

            /*
             * Because we might remove columns where affected by colspans, it's
             * easiest to simply redraw everything when columns are modified.
             * 
             * Yes, this is a TODO [[optimize]].
             */
            if (getRowCount() > 0
                    && getColumnConfiguration().getColumnCount() > 0) {
                refreshRows(0, getRowCount());
            }
        }

        void detachPossibleWidgetFromCell(Node cellNode) {
            // Detach possible widget
            Widget widget = getWidgetFromCell(cellNode);
            if (widget != null) {
                // Orphan.
                setParent(widget, null);

                // Physical detach.
                cellNode.removeChild(widget.getElement());
            }
        }

        protected void paintInsertColumns(final int offset,
                final int numberOfColumns, boolean frozen) {
            final NodeList<Node> childNodes = root.getChildNodes();

            for (int row = 0; row < childNodes.getLength(); row++) {
                final int rowHeight = getDefaultRowHeight();
                final Element tr = getTrByVisualIndex(row);

                Node referenceCell;
                if (offset != 0) {
                    referenceCell = tr.getChild(offset - 1);
                } else {
                    referenceCell = null;
                }

                for (int col = offset; col < offset + numberOfColumns; col++) {
                    final int colWidth = columnConfiguration
                            .getColumnWidthActual(col);
                    final Element cellElem = createCellElement(rowHeight,
                            colWidth);
                    referenceCell = insertAfterReferenceAndUpdateIt(tr,
                            cellElem, referenceCell);
                }
            }
            reapplyRowWidths();

            if (frozen) {
                for (int col = offset; col < offset + numberOfColumns; col++) {
                    setColumnFrozen(col, true);
                }
            }

            // this needs to be before the scrollbar adjustment.
            scroller.recalculateScrollbarsForVirtualViewport();

            int pixelsToInsertedColumn = columnConfiguration
                    .getCalculatedColumnsWidth(Range.withLength(0, offset));
            final boolean columnsWereAddedToTheLeftOfViewport = scroller.lastScrollLeft > pixelsToInsertedColumn;

            if (columnsWereAddedToTheLeftOfViewport) {
                int insertedColumnsWidth = columnConfiguration
                        .getCalculatedColumnsWidth(Range.withLength(offset,
                                numberOfColumns));
                horizontalScrollbar.setScrollPos(scroller.lastScrollLeft
                        + insertedColumnsWidth);
            }

            /*
             * Because we might insert columns where affected by colspans, it's
             * easiest to simply redraw everything when columns are modified.
             * 
             * Yes, this is a TODO [[optimize]].
             */
            if (getRowCount() > 0
                    && getColumnConfiguration().getColumnCount() > 1) {
                refreshRows(0, getRowCount());
            }
        }

        public void setColumnFrozen(int column, boolean frozen) {
            final NodeList<Node> childNodes = root.getChildNodes();

            for (int row = 0; row < childNodes.getLength(); row++) {
                final Element tr = childNodes.getItem(row).cast();

                Element cell = (Element) tr.getChild(column);
                if (frozen) {
                    cell.addClassName("frozen");
                } else {
                    cell.removeClassName("frozen");
                    position.reset(cell);
                }
            }

            if (frozen) {
                updateFreezePosition(column, scroller.lastScrollLeft);
            }
        }

        public void updateFreezePosition(int column, double scrollLeft) {
            final NodeList<Node> childNodes = root.getChildNodes();

            for (int row = 0; row < childNodes.getLength(); row++) {
                final Element tr = childNodes.getItem(row).cast();

                Element cell = (Element) tr.getChild(column);
                position.set(cell, scrollLeft, 0);
            }
        }

        /**
         * Iterates through all the cells in a column and returns the width of
         * the widest element in this RowContainer.
         * 
         * @param index
         *            the index of the column to inspect
         * @return the pixel width of the widest element in the indicated column
         */
        public int calculateMaxColWidth(int index) {
            Element row = root.getFirstChildElement();
            int maxWidth = 0;
            while (row != null) {
                final Element cell = (Element) row.getChild(index);
                final boolean isVisible = !cell.getStyle().getDisplay()
                        .equals(Display.NONE.getCssName());
                if (isVisible) {
                    maxWidth = Math.max(maxWidth, cell.getScrollWidth());
                }
                row = row.getNextSiblingElement();
            }
            return maxWidth;
        }

        /**
         * Reapplies all the cells' widths according to the calculated widths in
         * the column configuration.
         */
        public void reapplyColumnWidths() {
            Element row = root.getFirstChildElement();
            while (row != null) {
                Element cell = row.getFirstChildElement();
                int columnIndex = 0;
                while (cell != null) {
                    @SuppressWarnings("hiding")
                    final int width = getCalculatedColumnWidthWithColspan(cell,
                            columnIndex);

                    /*
                     * TODO Should Escalator implement ProvidesResize at some
                     * point, this is where we need to do that.
                     */
                    cell.getStyle().setWidth(width, Unit.PX);

                    cell = cell.getNextSiblingElement();
                    columnIndex++;
                }
                row = row.getNextSiblingElement();
            }

            reapplyRowWidths();
        }

        private int getCalculatedColumnWidthWithColspan(final Element cell,
                final int columnIndex) {
            final int colspan = cell.getPropertyInt(FlyweightCell.COLSPAN_ATTR);
            Range spannedColumns = Range.withLength(columnIndex, colspan);

            /*
             * Since browsers don't explode with overflowing colspans, escalator
             * shouldn't either.
             */
            if (spannedColumns.getEnd() > columnConfiguration.getColumnCount()) {
                spannedColumns = Range.between(columnIndex,
                        columnConfiguration.getColumnCount());
            }
            return columnConfiguration
                    .getCalculatedColumnsWidth(spannedColumns);
        }

        /**
         * Applies the total length of the columns to each row element.
         * <p>
         * <em>Note:</em> In contrast to {@link #reapplyColumnWidths()}, this
         * method only modifies the width of the {@code <tr>} element, not the
         * cells within.
         */
        protected void reapplyRowWidths() {
            int rowWidth = columnConfiguration.calculateRowWidth();

            com.google.gwt.dom.client.Element row = root.getFirstChildElement();
            while (row != null) {
                row.getStyle().setWidth(rowWidth, Unit.PX);
                row = row.getNextSiblingElement();
            }
        }

        /**
         * The primary style name for the container.
         * 
         * @param primaryStyleName
         *            the style name to use as prefix for all row and cell style
         *            names.
         */
        protected void setStylePrimaryName(String primaryStyleName) {
            String oldStyle = getStylePrimaryName();
            if (SharedUtil.equals(oldStyle, primaryStyleName)) {
                return;
            }

            this.primaryStyleName = primaryStyleName;

            // Update already rendered rows and cells
            Node row = root.getFirstChild();
            while (row != null) {
                Element rowElement = row.cast();
                UIObject.setStylePrimaryName(rowElement, primaryStyleName
                        + "-row");
                Node cell = row.getFirstChild();
                while (cell != null) {
                    Element cellElement = cell.cast();
                    UIObject.setStylePrimaryName(cellElement, primaryStyleName
                            + "-cell");
                    cell = cell.getNextSibling();
                }
                row = row.getNextSibling();
            }
        }

        /**
         * Returns the primary style name of the container.
         * 
         * @return The primary style name or <code>null</code> if not set.
         */
        protected String getStylePrimaryName() {
            return primaryStyleName;
        }

        @Override
        public void setDefaultRowHeight(int px) throws IllegalArgumentException {
            if (px < 1) {
                throw new IllegalArgumentException("Height must be positive. "
                        + px + " was given.");
            }

            defaultRowHeightShouldBeAutodetected = false;
            defaultRowHeight = px;
            reapplyDefaultRowHeights();
        }

        @Override
        public int getDefaultRowHeight() {
            return defaultRowHeight;
        }

        /**
         * The default height of rows has (most probably) changed.
         * <p>
         * Make sure that the displayed rows with a default height are updated
         * in height and top position.
         * <p>
         * <em>Note:</em>This implementation should not call
         * {@link Escalator#recalculateElementSizes()} - it is done by the
         * discretion of the caller of this method.
         */
        protected abstract void reapplyDefaultRowHeights();

        protected void reapplyRowHeight(final Element tr, final int heightPx) {
            Element cellElem = tr.getFirstChildElement().cast();
            while (cellElem != null) {
                cellElem.getStyle().setHeight(heightPx, Unit.PX);
                cellElem = cellElem.getNextSiblingElement();
            }

            /*
             * no need to apply height to tr-element, it'll be resized
             * implicitly.
             */
        }

        @SuppressWarnings("boxing")
        protected void setRowPosition(final Element tr, final int x, final int y) {
            position.set(tr, x, y);
            rowTopPositionMap.put(tr, y);
        }

        @SuppressWarnings("boxing")
        protected int getRowTop(final Element tr) {
            return rowTopPositionMap.get(tr);
        }

        protected void removeRowPosition(Element tr) {
            rowTopPositionMap.remove(tr);
        }

        public void autodetectRowHeight() {
            Scheduler.get().scheduleDeferred(new Scheduler.ScheduledCommand() {

                @Override
                public void execute() {
                    if (defaultRowHeightShouldBeAutodetected && isAttached()) {
                        final Element detectionTr = DOM.createTR();
                        detectionTr
                                .setClassName(getStylePrimaryName() + "-row");

                        final Element cellElem = DOM
                                .createElement(getCellElementTagName());
                        cellElem.setClassName(getStylePrimaryName() + "-cell");
                        cellElem.setInnerHTML("foo");

                        detectionTr.appendChild(cellElem);
                        root.appendChild(detectionTr);
                        defaultRowHeight = Math.max(1,
                                cellElem.getOffsetHeight());
                        root.removeChild(detectionTr);

                        if (root.hasChildNodes()) {
                            reapplyDefaultRowHeights();
                        }

                        defaultRowHeightShouldBeAutodetected = false;
                    }
                }
            });
        }
    }

    private abstract class AbstractStaticRowContainer extends
            AbstractRowContainer {
        public AbstractStaticRowContainer(final Element headElement) {
            super(headElement);
        }

        @Override
        protected void paintRemoveRows(final int index, final int numberOfRows) {
            for (int i = index; i < index + numberOfRows; i++) {
                final Element tr = (Element) root.getChild(index);
                for (int c = 0; c < tr.getChildCount(); c++) {
                    detachPossibleWidgetFromCell((Element) tr.getChild(c)
                            .cast());
                }
                tr.removeFromParent();
            }
            recalculateSectionHeight();
        }

        @Override
        protected Element getTrByVisualIndex(final int index)
                throws IndexOutOfBoundsException {
            if (index >= 0 && index < root.getChildCount()) {
                return (Element) root.getChild(index);
            } else {
                throw new IndexOutOfBoundsException("No such visual index: "
                        + index);
            }
        }

        @Override
        public void insertRows(int index, int numberOfRows) {
            super.insertRows(index, numberOfRows);
            recalculateElementSizes();
            applyHeightByRows();
        }

        @Override
        public void removeRows(int index, int numberOfRows) {
            super.removeRows(index, numberOfRows);
            recalculateElementSizes();
            applyHeightByRows();
        }

        @Override
        protected void reapplyDefaultRowHeights() {
            if (root.getChildCount() == 0) {
                return;
            }

            Profiler.enter("Escalator.AbstractStaticRowContainer.reapplyDefaultRowHeights");

            Element tr = root.getFirstChildElement().cast();
            while (tr != null) {
                reapplyRowHeight(tr, getDefaultRowHeight());
                tr = tr.getNextSiblingElement();
            }

            /*
             * Because all rows are immediately displayed in the static row
             * containers, the section's overall height has most probably
             * changed.
             */
            recalculateSectionHeight();

            Profiler.leave("Escalator.AbstractStaticRowContainer.reapplyDefaultRowHeights");
        }

        @Override
        protected void recalculateSectionHeight() {
            Profiler.enter("Escalator.AbstractStaticRowContainer.recalculateSectionHeight");

            int newHeight = calculateEstimatedTotalRowHeight();
            if (newHeight != heightOfSection) {
                heightOfSection = newHeight;
                sectionHeightCalculated();
                body.verifyEscalatorCount();
            }

            Profiler.leave("Escalator.AbstractStaticRowContainer.recalculateSectionHeight");
        }

        /**
         * Informs the row container that the height of its respective table
         * section has changed.
         * <p>
         * These calculations might affect some layouting logic, such as the
         * body is being offset by the footer, the footer needs to be readjusted
         * according to its height, and so on.
         * <p>
         * A table section is either header, body or footer.
         */
        protected abstract void sectionHeightCalculated();
    }

    private class HeaderRowContainer extends AbstractStaticRowContainer {
        public HeaderRowContainer(final Element headElement) {
            super(headElement);
        }

        @Override
        protected void sectionHeightCalculated() {
            bodyElem.getStyle().setMarginTop(heightOfSection, Unit.PX);
            verticalScrollbar.getElement().getStyle()
                    .setTop(heightOfSection, Unit.PX);
        }

        @Override
        protected String getCellElementTagName() {
            return "th";
        }

        @Override
        public void setStylePrimaryName(String primaryStyleName) {
            super.setStylePrimaryName(primaryStyleName);
            UIObject.setStylePrimaryName(root, primaryStyleName + "-header");
        }
    }

    private class FooterRowContainer extends AbstractStaticRowContainer {
        public FooterRowContainer(final Element footElement) {
            super(footElement);
        }

        @Override
        public void setStylePrimaryName(String primaryStyleName) {
            super.setStylePrimaryName(primaryStyleName);
            UIObject.setStylePrimaryName(root, primaryStyleName + "-footer");
        }

        @Override
        protected String getCellElementTagName() {
            return "td";
        }

        @Override
        protected void sectionHeightCalculated() {
            int vscrollHeight = (int) Math.floor(heightOfEscalator
                    - header.heightOfSection - footer.heightOfSection);

            final boolean horizontalScrollbarNeeded = columnConfiguration
                    .calculateRowWidth() > widthOfEscalator;
            if (horizontalScrollbarNeeded) {
                vscrollHeight -= horizontalScrollbar.getScrollbarThickness();
            }

            verticalScrollbar.setOffsetSize(vscrollHeight);
        }
    }

    private class BodyRowContainer extends AbstractRowContainer {
        /*
         * TODO [[optimize]]: check whether a native JsArray might be faster
         * than LinkedList
         */
        /**
         * The order in which row elements are rendered visually in the browser,
         * with the help of CSS tricks. Usually has nothing to do with the DOM
         * order.
         */
        private final LinkedList<Element> visualRowOrder = new LinkedList<Element>();

        /**
         * The logical index of the topmost row.
         * 
         * @deprecated Use the accessors {@link #setTopRowLogicalIndex(int)},
         *             {@link #updateTopRowLogicalIndex(int)} and
         *             {@link #getTopRowLogicalIndex()} instead
         */
        @Deprecated
        private int topRowLogicalIndex = 0;

        private void setTopRowLogicalIndex(int topRowLogicalIndex) {
            if (LogConfiguration.loggingIsEnabled(Level.INFO)) {
                Logger.getLogger("Escalator.BodyRowContainer").fine(
                        "topRowLogicalIndex: " + this.topRowLogicalIndex
                                + " -> " + topRowLogicalIndex);
            }
            assert topRowLogicalIndex >= 0 : "topRowLogicalIndex became negative";
            /*
             * if there's a smart way of evaluating and asserting the max index,
             * this would be a nice place to put it. I haven't found out an
             * effective and generic solution.
             */

            this.topRowLogicalIndex = topRowLogicalIndex;
        }

        private int getTopRowLogicalIndex() {
            return topRowLogicalIndex;
        }

        private void updateTopRowLogicalIndex(int diff) {
            setTopRowLogicalIndex(topRowLogicalIndex + diff);
        }

        public BodyRowContainer(final Element bodyElement) {
            super(bodyElement);
        }

        @Override
        public void setStylePrimaryName(String primaryStyleName) {
            super.setStylePrimaryName(primaryStyleName);
            UIObject.setStylePrimaryName(root, primaryStyleName + "-body");
        }

        public void updateEscalatorRowsOnScroll() {
            if (visualRowOrder.isEmpty()) {
                return;
            }

            boolean rowsWereMoved = false;

            final double topRowPos = getRowTop(visualRowOrder.getFirst());
            // TODO [[mpixscroll]]
            final double scrollTop = tBodyScrollTop;
            final double viewportOffset = topRowPos - scrollTop;

            /*
             * TODO [[optimize]] this if-else can most probably be refactored
             * into a neater block of code
             */

            if (viewportOffset > 0) {
                // there's empty room on top

                /*
                 * FIXME [[rowheight]]: coded to work only with default row
                 * heights - will not work with variable row heights
                 */
                int originalRowsToMove = (int) Math.ceil(viewportOffset
                        / getDefaultRowHeight());
                int rowsToMove = Math.min(originalRowsToMove,
                        root.getChildCount());

                final int end = root.getChildCount();
                final int start = end - rowsToMove;
                /*
                 * FIXME [[rowheight]]: coded to work only with default row
                 * heights - will not work with variable row heights
                 */
                final int logicalRowIndex = (int) (scrollTop / getDefaultRowHeight());
                moveAndUpdateEscalatorRows(Range.between(start, end), 0,
                        logicalRowIndex);

                updateTopRowLogicalIndex(-originalRowsToMove);

                rowsWereMoved = true;
            }

            else if (viewportOffset + getDefaultRowHeight() <= 0) {
                /*
                 * FIXME [[rowheight]]: coded to work only with default row
                 * heights - will not work with variable row heights
                 */

                /*
                 * the viewport has been scrolled more than the topmost visual
                 * row.
                 */

                int originalRowsToMove = (int) Math.abs(viewportOffset
                        / getDefaultRowHeight());
                int rowsToMove = Math.min(originalRowsToMove,
                        root.getChildCount());

                int logicalRowIndex;
                if (rowsToMove < root.getChildCount()) {
                    /*
                     * We scroll so little that we can just keep adding the rows
                     * below the current escalator
                     */
                    logicalRowIndex = getLogicalRowIndex(visualRowOrder
                            .getLast()) + 1;
                } else {
                    /*
                     * FIXME [[rowheight]]: coded to work only with default row
                     * heights - will not work with variable row heights
                     */
                    /*
                     * Since we're moving all escalator rows, we need to
                     * calculate the first logical row index from the scroll
                     * position.
                     */
                    logicalRowIndex = (int) (scrollTop / getDefaultRowHeight());
                }

                /*
                 * Since we're moving the viewport downwards, the visual index
                 * is always at the bottom. Note: Due to how
                 * moveAndUpdateEscalatorRows works, this will work out even if
                 * we move all the rows, and try to place them "at the end".
                 */
                final int targetVisualIndex = root.getChildCount();

                // make sure that we don't move rows over the data boundary
                boolean aRowWasLeftBehind = false;
                if (logicalRowIndex + rowsToMove > getRowCount()) {
                    /*
                     * TODO [[rowheight]]: with constant row heights, there's
                     * always exactly one row that will be moved beyond the data
                     * source, when viewport is scrolled to the end. This,
                     * however, isn't guaranteed anymore once row heights start
                     * varying.
                     */
                    rowsToMove--;
                    aRowWasLeftBehind = true;
                }

                moveAndUpdateEscalatorRows(Range.between(0, rowsToMove),
                        targetVisualIndex, logicalRowIndex);

                if (aRowWasLeftBehind) {
                    /*
                     * To keep visualRowOrder as a spatially contiguous block of
                     * rows, let's make sure that the one row we didn't move
                     * visually still stays with the pack.
                     */
                    final Range strayRow = Range.withOnly(0);

                    /*
                     * We cannot trust getLogicalRowIndex, because it hasn't yet
                     * been updated. But since we're leaving rows behind, it
                     * means we've scrolled to the bottom. So, instead, we
                     * simply count backwards from the end.
                     */
                    final int topLogicalIndex = getRowCount()
                            - visualRowOrder.size();
                    moveAndUpdateEscalatorRows(strayRow, 0, topLogicalIndex);
                }

                final int naiveNewLogicalIndex = getTopRowLogicalIndex()
                        + originalRowsToMove;
                final int maxLogicalIndex = getRowCount()
                        - visualRowOrder.size();
                setTopRowLogicalIndex(Math.min(naiveNewLogicalIndex,
                        maxLogicalIndex));

                rowsWereMoved = true;
            }

            if (rowsWereMoved) {
                fireRowVisibilityChangeEvent();
            }
        }

        @Override
        protected List<Element> paintInsertRows(final int index,
                final int numberOfRows) {
            if (numberOfRows == 0) {
                return Collections.emptyList();
            }

            /*
             * TODO: this method should probably only add physical rows, and not
             * populate them - let everything be populated as appropriate by the
             * logic that follows.
             * 
             * This also would lead to the fact that paintInsertRows wouldn't
             * need to return anything.
             */
            final List<Element> addedRows = fillAndPopulateEscalatorRowsIfNeeded(
                    index, numberOfRows);

            /*
             * insertRows will always change the number of rows - update the
             * scrollbar sizes.
             */
            scroller.recalculateScrollbarsForVirtualViewport();

            /*
             * FIXME [[rowheight]]: coded to work only with default row heights
             * - will not work with variable row heights
             */
            final boolean addedRowsAboveCurrentViewport = index
                    * getDefaultRowHeight() < getScrollTop();
            final boolean addedRowsBelowCurrentViewport = index
                    * getDefaultRowHeight() > getScrollTop()
                    + calculateHeight();

            if (addedRowsAboveCurrentViewport) {
                /*
                 * We need to tweak the virtual viewport (scroll handle
                 * positions, table "scroll position" and row locations), but
                 * without re-evaluating any rows.
                 */

                /*
                 * FIXME [[rowheight]]: coded to work only with default row
                 * heights - will not work with variable row heights
                 */
                final int yDelta = numberOfRows * getDefaultRowHeight();
                adjustScrollPosIgnoreEvents(yDelta);
                updateTopRowLogicalIndex(numberOfRows);
            }

            else if (addedRowsBelowCurrentViewport) {
                // NOOP, we already recalculated scrollbars.
            }

            else { // some rows were added inside the current viewport

                final int unupdatedLogicalStart = index + addedRows.size();
                final int visualOffset = getLogicalRowIndex(visualRowOrder
                        .getFirst());

                /*
                 * At this point, we have added new escalator rows, if so
                 * needed.
                 * 
                 * If more rows were added than the new escalator rows can
                 * account for, we need to start to spin the escalator to update
                 * the remaining rows aswell.
                 */
                final int rowsStillNeeded = numberOfRows - addedRows.size();
                final Range unupdatedVisual = convertToVisual(Range.withLength(
                        unupdatedLogicalStart, rowsStillNeeded));
                final int end = root.getChildCount();
                final int start = end - unupdatedVisual.length();
                final int visualTargetIndex = unupdatedLogicalStart
                        - visualOffset;
                moveAndUpdateEscalatorRows(Range.between(start, end),
                        visualTargetIndex, unupdatedLogicalStart);

                /*
                 * FIXME [[rowheight]]: coded to work only with default row
                 * heights - will not work with variable row heights
                 */
                // move the surrounding rows to their correct places.
                int rowTop = (unupdatedLogicalStart + (end - start))
                        * getDefaultRowHeight();
                final ListIterator<Element> i = visualRowOrder
                        .listIterator(visualTargetIndex + (end - start));
                while (i.hasNext()) {
                    final Element tr = i.next();
                    setRowPosition(tr, 0, rowTop);
                    /*
                     * FIXME [[rowheight]]: coded to work only with default row
                     * heights - will not work with variable row heights
                     */
                    rowTop += getDefaultRowHeight();
                }

                fireRowVisibilityChangeEvent();
            }
            return addedRows;
        }

        /**
         * Move escalator rows around, and make sure everything gets
         * appropriately repositioned and repainted.
         * 
         * @param visualSourceRange
         *            the range of rows to move to a new place
         * @param visualTargetIndex
         *            the visual index where the rows will be placed to
         * @param logicalTargetIndex
         *            the logical index to be assigned to the first moved row
         * @throws IllegalArgumentException
         *             if any of <code>visualSourceRange.getStart()</code>,
         *             <code>visualTargetIndex</code> or
         *             <code>logicalTargetIndex</code> is a negative number; or
         *             if <code>visualTargetInfo</code> is greater than the
         *             number of escalator rows.
         */
        private void moveAndUpdateEscalatorRows(final Range visualSourceRange,
                final int visualTargetIndex, final int logicalTargetIndex)
                throws IllegalArgumentException {

            if (visualSourceRange.isEmpty()) {
                return;
            }

            if (visualSourceRange.getStart() < 0) {
                throw new IllegalArgumentException(
                        "Logical source start must be 0 or greater (was "
                                + visualSourceRange.getStart() + ")");
            } else if (logicalTargetIndex < 0) {
                throw new IllegalArgumentException(
                        "Logical target must be 0 or greater");
            } else if (visualTargetIndex < 0) {
                throw new IllegalArgumentException(
                        "Visual target must be 0 or greater");
            } else if (visualTargetIndex > root.getChildCount()) {
                throw new IllegalArgumentException(
                        "Visual target must not be greater than the number of escalator rows");
            } else if (logicalTargetIndex + visualSourceRange.length() > getRowCount()) {
                final int logicalEndIndex = logicalTargetIndex
                        + visualSourceRange.length() - 1;
                throw new IllegalArgumentException(
                        "Logical target leads to rows outside of the data range ("
                                + logicalTargetIndex + ".." + logicalEndIndex
                                + ")");
            }

            /*
             * Since we move a range into another range, the indices might move
             * about. Having 10 rows, if we move 0..1 to index 10 (to the end of
             * the collection), the target range will end up being 8..9, instead
             * of 10..11.
             * 
             * This applies only if we move elements forward in the collection,
             * not backward.
             */
            final int adjustedVisualTargetIndex;
            if (visualSourceRange.getStart() < visualTargetIndex) {
                adjustedVisualTargetIndex = visualTargetIndex
                        - visualSourceRange.length();
            } else {
                adjustedVisualTargetIndex = visualTargetIndex;
            }

            if (visualSourceRange.getStart() != adjustedVisualTargetIndex) {

                /*
                 * Reorder the rows to their correct places within
                 * visualRowOrder (unless rows are moved back to their original
                 * places)
                 */

                /*
                 * TODO [[optimize]]: move whichever set is smaller: the ones
                 * explicitly moved, or the others. So, with 10 escalator rows,
                 * if we are asked to move idx[0..8] to the end of the list,
                 * it's faster to just move idx[9] to the beginning.
                 */

                final List<Element> removedRows = new ArrayList<Element>(
                        visualSourceRange.length());
                for (int i = 0; i < visualSourceRange.length(); i++) {
                    final Element tr = visualRowOrder.remove(visualSourceRange
                            .getStart());
                    removedRows.add(tr);
                }
                visualRowOrder.addAll(adjustedVisualTargetIndex, removedRows);
            }

            { // Refresh the contents of the affected rows
                final ListIterator<Element> iter = visualRowOrder
                        .listIterator(adjustedVisualTargetIndex);
                for (int logicalIndex = logicalTargetIndex; logicalIndex < logicalTargetIndex
                        + visualSourceRange.length(); logicalIndex++) {
                    final Element tr = iter.next();
                    refreshRow(tr, logicalIndex);
                }
            }

            { // Reposition the rows that were moved
                /*
                 * FIXME [[rowheight]]: coded to work only with default row
                 * heights - will not work with variable row heights
                 */
                int newRowTop = logicalTargetIndex * getDefaultRowHeight();

                final ListIterator<Element> iter = visualRowOrder
                        .listIterator(adjustedVisualTargetIndex);
                for (int i = 0; i < visualSourceRange.length(); i++) {
                    final Element tr = iter.next();
                    setRowPosition(tr, 0, newRowTop);
                    /*
                     * FIXME [[rowheight]]: coded to work only with default row
                     * heights - will not work with variable row heights
                     */
                    newRowTop += getDefaultRowHeight();
                }
            }
        }

        /**
         * Adjust the scroll position without having the scroll handler have any
         * side-effects.
         * <p>
         * <em>Note:</em> {@link Scroller#onScroll()} <em>will</em> be
         * triggered, but it will not do anything, with the help of
         * {@link Escalator#internalScrollEventCalls}.
         * 
         * @param yDelta
         *            the delta of pixels to scrolls. A positive value moves the
         *            viewport downwards, while a negative value moves the
         *            viewport upwards
         */
        public void adjustScrollPosIgnoreEvents(final double yDelta) {
            if (yDelta == 0) {
                return;
            }

            internalScrollEventCalls++;
            verticalScrollbar.setScrollPosByDelta(yDelta);

            /*
             * FIXME [[rowheight]]: coded to work only with default row heights
             * - will not work with variable row heights
             */
            final int rowTopPos = (int) yDelta
                    - ((int) yDelta % getDefaultRowHeight());
            for (final Element tr : visualRowOrder) {
                setRowPosition(tr, 0, getRowTop(tr) + rowTopPos);
            }
            setBodyScrollPosition(tBodyScrollLeft, tBodyScrollTop + yDelta);
        }

        /**
         * Adds new physical escalator rows to the DOM at the given index if
         * there's still a need for more escalator rows.
         * <p>
         * If Escalator already is at (or beyond) max capacity, this method does
         * nothing to the DOM.
         * 
         * @param index
         *            the index at which to add new escalator rows.
         *            <em>Note:</em>It is assumed that the index is both the
         *            visual index and the logical index.
         * @param numberOfRows
         *            the number of rows to add at <code>index</code>
         * @return a list of the added rows
         */
        private List<Element> fillAndPopulateEscalatorRowsIfNeeded(
                final int index, final int numberOfRows) {

            final int escalatorRowsStillFit = getMaxEscalatorRowCapacity()
                    - root.getChildCount();
            final int escalatorRowsNeeded = Math.min(numberOfRows,
                    escalatorRowsStillFit);

            if (escalatorRowsNeeded > 0) {

                final List<Element> addedRows = super.paintInsertRows(index,
                        escalatorRowsNeeded);
                visualRowOrder.addAll(index, addedRows);

                /*
                 * We need to figure out the top positions for the rows we just
                 * added.
                 */
                for (int i = 0; i < addedRows.size(); i++) {
                    /*
                     * FIXME [[rowheight]]: coded to work only with default row
                     * heights - will not work with variable row heights
                     */
                    setRowPosition(addedRows.get(i), 0, (index + i)
                            * getDefaultRowHeight());
                }

                /* Move the other rows away from above the added escalator rows */
                for (int i = index + addedRows.size(); i < visualRowOrder
                        .size(); i++) {
                    final Element tr = visualRowOrder.get(i);
                    /*
                     * FIXME [[rowheight]]: coded to work only with default row
                     * heights - will not work with variable row heights
                     */
                    setRowPosition(tr, 0, i * getDefaultRowHeight());
                }

                return addedRows;
            } else {
                return new ArrayList<Element>();
            }
        }

        private int getMaxEscalatorRowCapacity() {
            /*
             * FIXME [[rowheight]]: coded to work only with default row heights
             * - will not work with variable row heights
             */
            final int maxEscalatorRowCapacity = (int) Math
                    .ceil(calculateHeight() / getDefaultRowHeight()) + 1;

            /*
             * maxEscalatorRowCapacity can become negative if the headers and
             * footers start to overlap. This is a crazy situation, but Vaadin
             * blinks the components a lot, so it's feasible.
             */
            return Math.max(0, maxEscalatorRowCapacity);
        }

        @Override
        protected void paintRemoveRows(final int index, final int numberOfRows) {

            final Range viewportRange = Range.withLength(
                    getLogicalRowIndex(visualRowOrder.getFirst()),
                    visualRowOrder.size());

            final Range removedRowsRange = Range
                    .withLength(index, numberOfRows);

            final Range[] partitions = removedRowsRange
                    .partitionWith(viewportRange);
            final Range removedAbove = partitions[0];
            final Range removedLogicalInside = partitions[1];
            final Range removedVisualInside = convertToVisual(removedLogicalInside);

            /*
             * TODO: extract the following if-block to a separate method. I'll
             * leave this be inlined for now, to make linediff-based code
             * reviewing easier. Probably will be moved in the following patch
             * set.
             */

            /*
             * Adjust scroll position in one of two scenarios:
             * 
             * 1) Rows were removed above. Then we just need to adjust the
             * scrollbar by the height of the removed rows.
             * 
             * 2) There are no logical rows above, and at least the first (if
             * not more) visual row is removed. Then we need to snap the scroll
             * position to the first visible row (i.e. reset scroll position to
             * absolute 0)
             * 
             * The logic is optimized in such a way that the
             * adjustScrollPosIgnoreEvents is called only once, to avoid extra
             * reflows, and thus the code might seem a bit obscure.
             */
            final boolean firstVisualRowIsRemoved = !removedVisualInside
                    .isEmpty() && removedVisualInside.getStart() == 0;

            if (!removedAbove.isEmpty() || firstVisualRowIsRemoved) {
                /*
                 * FIXME [[rowheight]]: coded to work only with default row
                 * heights - will not work with variable row heights
                 */
                final int yDelta = removedAbove.length()
                        * getDefaultRowHeight();
                final int firstLogicalRowHeight = getDefaultRowHeight();
                final boolean removalScrollsToShowFirstLogicalRow = verticalScrollbar
                        .getScrollPos() - yDelta < firstLogicalRowHeight;

                if (removedVisualInside.isEmpty()
                        && (!removalScrollsToShowFirstLogicalRow || !firstVisualRowIsRemoved)) {
                    /*
                     * rows were removed from above the viewport, so all we need
                     * to do is to adjust the scroll position to account for the
                     * removed rows
                     */
                    adjustScrollPosIgnoreEvents(-yDelta);
                } else if (removalScrollsToShowFirstLogicalRow) {
                    /*
                     * It seems like we've removed all rows from above, and also
                     * into the current viewport. This means we'll need to even
                     * out the scroll position to exactly 0 (i.e. adjust by the
                     * current negative scrolltop, presto!), so that it isn't
                     * aligned funnily
                     */
                    adjustScrollPosIgnoreEvents(-verticalScrollbar
                            .getScrollPos());
                }
            }

            // ranges evaluated, let's do things.
            if (!removedVisualInside.isEmpty()) {
                int escalatorRowCount = bodyElem.getChildCount();

                /*
                 * If we're left with less rows than the number of escalators,
                 * remove the unused ones.
                 */
                final int escalatorRowsToRemove = escalatorRowCount
                        - getRowCount();
                if (escalatorRowsToRemove > 0) {
                    for (int i = 0; i < escalatorRowsToRemove; i++) {
                        final Element tr = visualRowOrder
                                .remove(removedVisualInside.getStart());
                        for (int c = 0; c < tr.getChildCount(); c++) {
                            detachPossibleWidgetFromCell((Element) tr.getChild(
                                    c).cast());
                        }
                        tr.removeFromParent();
                        removeRowPosition(tr);
                    }
                    escalatorRowCount -= escalatorRowsToRemove;

                    /*
                     * Because we're removing escalator rows, we don't have
                     * anything to scroll by. Let's make sure the viewport is
                     * scrolled to top, to render any rows possibly left above.
                     */
                    body.setBodyScrollPosition(tBodyScrollLeft, 0);

                    /*
                     * We might have removed some rows from the middle, so let's
                     * make sure we're not left with any holes. Also remember:
                     * visualIndex == logicalIndex applies now.
                     */
                    final int dirtyRowsStart = removedLogicalInside.getStart();
                    for (int i = dirtyRowsStart; i < escalatorRowCount; i++) {
                        final Element tr = visualRowOrder.get(i);
                        /*
                         * FIXME [[rowheight]]: coded to work only with default
                         * row heights - will not work with variable row heights
                         */
                        setRowPosition(tr, 0, i * getDefaultRowHeight());
                    }

                    /*
                     * this is how many rows appeared into the viewport from
                     * below
                     */
                    final int rowsToUpdateDataOn = numberOfRows
                            - escalatorRowsToRemove;
                    final int start = Math.max(0, escalatorRowCount
                            - rowsToUpdateDataOn);
                    final int end = escalatorRowCount;
                    for (int i = start; i < end; i++) {
                        final Element tr = visualRowOrder.get(i);
                        refreshRow(tr, i);
                    }
                }

                else {
                    // No escalator rows need to be removed.

                    /*
                     * Two things (or a combination thereof) can happen:
                     * 
                     * 1) We're scrolled to the bottom, the last rows are
                     * removed. SOLUTION: moveAndUpdateEscalatorRows the
                     * bottommost rows, and place them at the top to be
                     * refreshed.
                     * 
                     * 2) We're scrolled somewhere in the middle, arbitrary rows
                     * are removed. SOLUTION: moveAndUpdateEscalatorRows the
                     * removed rows, and place them at the bottom to be
                     * refreshed.
                     * 
                     * Since a combination can also happen, we need to handle
                     * this in a smart way, all while avoiding
                     * double-refreshing.
                     */

                    /*
                     * FIXME [[rowheight]]: coded to work only with default row
                     * heights - will not work with variable row heights
                     */
                    final int contentBottom = getRowCount()
                            * getDefaultRowHeight();
                    final int viewportBottom = (int) (tBodyScrollTop + calculateHeight());
                    if (viewportBottom <= contentBottom) {
                        /*
                         * We're in the middle of the row container, everything
                         * is added to the bottom
                         */
                        paintRemoveRowsAtMiddle(removedLogicalInside,
                                removedVisualInside, 0);
                    }

                    else if (contentBottom
                            + (numberOfRows * getDefaultRowHeight())
                            - viewportBottom < getDefaultRowHeight()) {
                        /*
                         * FIXME [[rowheight]]: above coded to work only with
                         * default row heights - will not work with variable row
                         * heights
                         */

                        /*
                         * We're at the end of the row container, everything is
                         * added to the top.
                         */
                        paintRemoveRowsAtBottom(removedLogicalInside,
                                removedVisualInside);
                        updateTopRowLogicalIndex(-removedLogicalInside.length());
                    }

                    else {
                        /*
                         * We're in a combination, where we need to both scroll
                         * up AND show new rows at the bottom.
                         * 
                         * Example: Scrolled down to show the second to last
                         * row. Remove two. Viewport scrolls up, revealing the
                         * row above row. The last element collapses up and into
                         * view.
                         * 
                         * Reminder: this use case handles only the case when
                         * there are enough escalator rows to still render a
                         * full view. I.e. all escalator rows will _always_ be
                         * populated
                         */
                        /*-
                         *  1       1      |1| <- newly rendered
                         * |2|     |2|     |2|
                         * |3| ==> |*| ==> |5| <- newly rendered
                         * |4|     |*|
                         *  5       5
                         *  
                         *  1       1      |1| <- newly rendered
                         * |2|     |*|     |4|
                         * |3| ==> |*| ==> |5| <- newly rendered
                         * |4|     |4|
                         *  5       5
                         */

                        /*
                         * STEP 1:
                         * 
                         * reorganize deprecated escalator rows to bottom, but
                         * don't re-render anything yet
                         */
                        /*-
                         *  1       1       1
                         * |2|     |*|     |4|
                         * |3| ==> |*| ==> |*|
                         * |4|     |4|     |*|
                         *  5       5       5
                         */
                        double newTop = getRowTop(visualRowOrder
                                .get(removedVisualInside.getStart()));
                        for (int i = 0; i < removedVisualInside.length(); i++) {
                            final Element tr = visualRowOrder
                                    .remove(removedVisualInside.getStart());
                            visualRowOrder.addLast(tr);
                        }

                        for (int i = removedVisualInside.getStart(); i < escalatorRowCount; i++) {
                            final Element tr = visualRowOrder.get(i);
                            setRowPosition(tr, 0, (int) newTop);

                            /*
                             * FIXME [[rowheight]]: coded to work only with
                             * default row heights - will not work with variable
                             * row heights
                             */
                            newTop += getDefaultRowHeight();
                        }

                        /*
                         * STEP 2:
                         * 
                         * manually scroll
                         */
                        /*-
                         *  1      |1| <-- newly rendered (by scrolling)
                         * |4|     |4|
                         * |*| ==> |*|
                         * |*|       
                         *  5       5
                         */
                        final double newScrollTop = contentBottom
                                - calculateHeight();
                        setScrollTop(newScrollTop);
                        /*
                         * Manually call the scroll handler, so we get immediate
                         * effects in the escalator.
                         */
                        scroller.onScroll();
                        internalScrollEventCalls++;

                        /*
                         * Move the bottommost (n+1:th) escalator row to top,
                         * because scrolling up doesn't handle that for us
                         * automatically
                         */
                        moveAndUpdateEscalatorRows(
                                Range.withOnly(escalatorRowCount - 1),
                                0,
                                getLogicalRowIndex(visualRowOrder.getFirst()) - 1);
                        updateTopRowLogicalIndex(-1);

                        /*
                         * STEP 3:
                         * 
                         * update remaining escalator rows
                         */
                        /*-
                         * |1|     |1|
                         * |4| ==> |4|
                         * |*|     |5| <-- newly rendered
                         *           
                         *  5
                         */

                        /*
                         * FIXME [[rowheight]]: coded to work only with default
                         * row heights - will not work with variable row heights
                         */
                        final int rowsScrolled = (int) (Math
                                .ceil((viewportBottom - (double) contentBottom)
                                        / getDefaultRowHeight()));
                        final int start = escalatorRowCount
                                - (removedVisualInside.length() - rowsScrolled);
                        final Range visualRefreshRange = Range.between(start,
                                escalatorRowCount);
                        final int logicalTargetIndex = getLogicalRowIndex(visualRowOrder
                                .getFirst()) + start;
                        // in-place move simply re-renders the rows.
                        moveAndUpdateEscalatorRows(visualRefreshRange, start,
                                logicalTargetIndex);
                    }
                }
            }

            updateTopRowLogicalIndex(-removedAbove.length());

            /*
             * this needs to be done after the escalator has been shrunk down,
             * or it won't work correctly (due to setScrollTop invocation)
             */
            scroller.recalculateScrollbarsForVirtualViewport();

            fireRowVisibilityChangeEvent();
        }

        private void paintRemoveRowsAtMiddle(final Range removedLogicalInside,
                final Range removedVisualInside, final int logicalOffset) {
            /*-
             *  :       :       :
             * |2|     |2|     |2|
             * |3| ==> |*| ==> |4|
             * |4|     |4|     |6| <- newly rendered
             *  :       :       :
             */

            final int escalatorRowCount = visualRowOrder.size();

            final int logicalTargetIndex = getLogicalRowIndex(visualRowOrder
                    .getLast())
                    - (removedVisualInside.length() - 1)
                    + logicalOffset;
            moveAndUpdateEscalatorRows(removedVisualInside, escalatorRowCount,
                    logicalTargetIndex);

            // move the surrounding rows to their correct places.
            final ListIterator<Element> iterator = visualRowOrder
                    .listIterator(removedVisualInside.getStart());

            /*
             * FIXME [[rowheight]]: coded to work only with default row heights
             * - will not work with variable row heights
             */
            int rowTop = (removedLogicalInside.getStart() + logicalOffset)
                    * getDefaultRowHeight();
            for (int i = removedVisualInside.getStart(); i < escalatorRowCount
                    - removedVisualInside.length(); i++) {
                final Element tr = iterator.next();
                setRowPosition(tr, 0, rowTop);
                /*
                 * FIXME [[rowheight]]: coded to work only with default row
                 * heights - will not work with variable row heights
                 */
                rowTop += getDefaultRowHeight();
            }
        }

        private void paintRemoveRowsAtBottom(final Range removedLogicalInside,
                final Range removedVisualInside) {
            /*-
             *                  :
             *  :       :      |4| <- newly rendered
             * |5|     |5|     |5|
             * |6| ==> |*| ==> |7|
             * |7|     |7|     
             */

            final int logicalTargetIndex = getLogicalRowIndex(visualRowOrder
                    .getFirst()) - removedVisualInside.length();
            moveAndUpdateEscalatorRows(removedVisualInside, 0,
                    logicalTargetIndex);

            // move the surrounding rows to their correct places.
            final ListIterator<Element> iterator = visualRowOrder
                    .listIterator(removedVisualInside.getEnd());
            /*
             * FIXME [[rowheight]]: coded to work only with default row heights
             * - will not work with variable row heights
             */
            int rowTop = removedLogicalInside.getStart()
                    * getDefaultRowHeight();
            while (iterator.hasNext()) {
                final Element tr = iterator.next();
                setRowPosition(tr, 0, rowTop);
                /*
                 * FIXME [[rowheight]]: coded to work only with default row
                 * heights - will not work with variable row heights
                 */
                rowTop += getDefaultRowHeight();
            }
        }

        private int getLogicalRowIndex(final Element element) {
            assert element.getParentNode() == root : "The given element isn't a row element in the body";
            int internalIndex = visualRowOrder.indexOf(element);
            return getTopRowLogicalIndex() + internalIndex;
        }

        @Override
        protected void recalculateSectionHeight() {
            // NOOP for body, since it doesn't make any sense.
        }

        /**
         * Adjusts the row index and number to be relevant for the current
         * virtual viewport.
         * <p>
         * It converts a logical range of rows index to the matching visual
         * range, truncating the resulting range with the viewport.
         * <p>
         * <ul>
         * <li>Escalator contains logical rows 0..100
         * <li>Current viewport showing logical rows 20..29
         * <li>convertToVisual([20..29]) &rarr; [0..9]
         * <li>convertToVisual([15..24]) &rarr; [0..4]
         * <li>convertToVisual([25..29]) &rarr; [5..9]
         * <li>convertToVisual([26..39]) &rarr; [6..9]
         * <li>convertToVisual([0..5]) &rarr; [0..-1] <em>(empty)</em>
         * <li>convertToVisual([35..1]) &rarr; [0..-1] <em>(empty)</em>
         * <li>convertToVisual([0..100]) &rarr; [0..9]
         * </ul>
         * 
         * @return a logical range converted to a visual range, truncated to the
         *         current viewport. The first visual row has the index 0.
         */
        private Range convertToVisual(final Range logicalRange) {
            if (logicalRange.isEmpty()) {
                return logicalRange;
            } else if (visualRowOrder.isEmpty()) {
                // empty range
                return Range.withLength(0, 0);
            }

            /*
             * TODO [[rowheight]]: these assumptions will be totally broken with
             * variable row heights.
             */
            final int maxEscalatorRows = getMaxEscalatorRowCapacity();
            final int currentTopRowIndex = getLogicalRowIndex(visualRowOrder
                    .getFirst());

            final Range[] partitions = logicalRange.partitionWith(Range
                    .withLength(currentTopRowIndex, maxEscalatorRows));
            final Range insideRange = partitions[1];
            return insideRange.offsetBy(-currentTopRowIndex);
        }

        @Override
        protected String getCellElementTagName() {
            return "td";
        }

        /**
         * Calculates the height of the {@code <tbody>} as it should be rendered
         * in the DOM.
         */
        private double calculateHeight() {
            final int tableHeight = tableWrapper.getOffsetHeight();
            final double footerHeight = footer.heightOfSection;
            final double headerHeight = header.heightOfSection;
            return tableHeight - footerHeight - headerHeight;
        }

        @Override
        public void refreshRows(final int index, final int numberOfRows) {
            Profiler.enter("Escalator.BodyRowContainer.refreshRows");

            final Range visualRange = convertToVisual(Range.withLength(index,
                    numberOfRows));

            if (!visualRange.isEmpty()) {
                final int firstLogicalRowIndex = getLogicalRowIndex(visualRowOrder
                        .getFirst());
                for (int rowNumber = visualRange.getStart(); rowNumber < visualRange
                        .getEnd(); rowNumber++) {
                    refreshRow(visualRowOrder.get(rowNumber),
                            firstLogicalRowIndex + rowNumber);
                }
            }

            Profiler.leave("Escalator.BodyRowContainer.refreshRows");
        }

        @Override
        protected Element getTrByVisualIndex(final int index)
                throws IndexOutOfBoundsException {
            if (index >= 0 && index < visualRowOrder.size()) {
                return visualRowOrder.get(index);
            } else {
                throw new IndexOutOfBoundsException("No such visual index: "
                        + index);
            }
        }

        private void setBodyScrollPosition(final double scrollLeft,
                final double scrollTop) {
            tBodyScrollLeft = scrollLeft;
            tBodyScrollTop = scrollTop;
            position.set(bodyElem, -tBodyScrollLeft, -tBodyScrollTop);
        }

        /**
         * Make sure that there is a correct amount of escalator rows: Add more
         * if needed, or remove any superfluous ones.
         * <p>
         * This method should be called when e.g. the height of the Escalator
         * changes.
         * <p>
         * <em>Note:</em> This method will make sure that the escalator rows are
         * placed in the proper places. By default new rows are added below, but
         * if the content is scrolled down, the rows are populated on top
         * instead.
         */
        public void verifyEscalatorCount() {
            /*
             * This method indeed has a smell very similar to paintRemoveRows
             * and paintInsertRows.
             * 
             * Unfortunately, those the code can't trivially be shared, since
             * there are some slight differences in the respective
             * responsibilities. The "paint" methods fake the addition and
             * removal of rows, and make sure to either push existing data out
             * of view, or draw new data into view. Only in some special cases
             * will the DOM element count change.
             * 
             * This method, however, has the explicit responsibility to verify
             * that when "something" happens, we still have the correct amount
             * of escalator rows in the DOM, and if not, we make sure to modify
             * that count. Only in some special cases do we need to take into
             * account other things than simply modifying the DOM element count.
             */

            Profiler.enter("Escalator.BodyRowContainer.verifyEscalatorCount");

            if (!isAttached()) {
                return;
            }

            final int maxEscalatorRows = getMaxEscalatorRowCapacity();
            final int neededEscalatorRows = Math.min(maxEscalatorRows,
                    body.getRowCount());
            final int neededEscalatorRowsDiff = neededEscalatorRows
                    - visualRowOrder.size();

            if (neededEscalatorRowsDiff > 0) {
                // needs more

                /*
                 * This is a workaround for the issue where we might be scrolled
                 * to the bottom, and the widget expands beyond the content
                 * range
                 */

                final int index = visualRowOrder.size();
                final int nextLastLogicalIndex;
                if (!visualRowOrder.isEmpty()) {
                    nextLastLogicalIndex = getLogicalRowIndex(visualRowOrder
                            .getLast()) + 1;
                } else {
                    nextLastLogicalIndex = 0;
                }

                final boolean contentWillFit = nextLastLogicalIndex < getRowCount()
                        - neededEscalatorRowsDiff;
                if (contentWillFit) {
                    final List<Element> addedRows = fillAndPopulateEscalatorRowsIfNeeded(
                            index, neededEscalatorRowsDiff);

                    /*
                     * Since fillAndPopulateEscalatorRowsIfNeeded operates on
                     * the assumption that index == visual index == logical
                     * index, we thank for the added escalator rows, but since
                     * they're painted in the wrong CSS position, we need to
                     * move them to their actual locations.
                     * 
                     * Note: this is the second (see body.paintInsertRows)
                     * occasion where fillAndPopulateEscalatorRowsIfNeeded would
                     * behave "more correctly" if it only would add escalator
                     * rows to the DOM and appropriate bookkeping, and not
                     * actually populate them :/
                     */
                    moveAndUpdateEscalatorRows(
                            Range.withLength(index, addedRows.size()), index,
                            nextLastLogicalIndex);
                } else {
                    /*
                     * TODO [[optimize]]
                     * 
                     * We're scrolled so far down that all rows can't be simply
                     * appended at the end, since we might start displaying
                     * escalator rows that don't exist. To avoid the mess that
                     * is body.paintRemoveRows, this is a dirty hack that dumbs
                     * the problem down to a more basic and already-solved
                     * problem:
                     * 
                     * 1) scroll all the way up 2) add the missing escalator
                     * rows 3) scroll back to the original position.
                     * 
                     * Letting the browser scroll back to our original position
                     * will automatically solve any possible overflow problems,
                     * since the browser will not allow us to scroll beyond the
                     * actual content.
                     */

                    final double oldScrollTop = getScrollTop();
                    setScrollTop(0);
                    scroller.onScroll();
                    fillAndPopulateEscalatorRowsIfNeeded(index,
                            neededEscalatorRowsDiff);
                    setScrollTop(oldScrollTop);
                    scroller.onScroll();
                    internalScrollEventCalls++;
                }
            }

            else if (neededEscalatorRowsDiff < 0) {
                // needs less

                final ListIterator<Element> iter = visualRowOrder
                        .listIterator(visualRowOrder.size());
                for (int i = 0; i < -neededEscalatorRowsDiff; i++) {
                    final Element last = iter.previous();
                    for (int c = 0; c < last.getChildCount(); c++) {
                        detachPossibleWidgetFromCell((Element) last.getChild(c)
                                .cast());
                    }
                    last.removeFromParent();
                    iter.remove();
                }

                /*
                 * If we were scrolled to the bottom so that we didn't have an
                 * extra escalator row at the bottom, we'll probably end up with
                 * blank space at the bottom of the escalator, and one extra row
                 * above the header.
                 * 
                 * Experimentation idea #1: calculate "scrollbottom" vs content
                 * bottom and remove one row from top, rest from bottom. This
                 * FAILED, since setHeight has already happened, thus we never
                 * will detect ourselves having been scrolled all the way to the
                 * bottom.
                 */

                if (!visualRowOrder.isEmpty()) {
                    final int firstRowTop = getRowTop(visualRowOrder.getFirst());
                    /*
                     * FIXME [[rowheight]]: coded to work only with default row
                     * heights - will not work with variable row heights
                     */
                    final double firstRowMinTop = tBodyScrollTop
                            - getDefaultRowHeight();
                    if (firstRowTop < firstRowMinTop) {
                        final int newLogicalIndex = getLogicalRowIndex(visualRowOrder
                                .getLast()) + 1;
                        moveAndUpdateEscalatorRows(Range.withOnly(0),
                                visualRowOrder.size(), newLogicalIndex);
                    }
                }
            }

            if (neededEscalatorRowsDiff != 0) {
                fireRowVisibilityChangeEvent();
            }

            Profiler.leave("Escalator.BodyRowContainer.verifyEscalatorCount");
        }

        @Override
        protected void reapplyDefaultRowHeights() {
            if (visualRowOrder.isEmpty()) {
                return;
            }

            /*
             * As an intermediate step between hard-coded row heights to crazily
             * varying row heights, Escalator will support the modification of
             * the default row height (which is applied to all rows).
             * 
             * This allows us to do some assumptions and simplifications for
             * now. This code is intended to be quite short-lived, but gives
             * insight into what needs to be done when row heights change in the
             * body, in a general sense.
             * 
             * TODO [[rowheight]] remove this comment once row heights may
             * genuinely vary.
             */

            Profiler.enter("Escalator.BodyRowContainer.reapplyDefaultRowHeights");

            /* step 1: resize and reposition rows */
            for (int i = 0; i < visualRowOrder.size(); i++) {
                Element tr = visualRowOrder.get(i);
                reapplyRowHeight(tr, getDefaultRowHeight());

                final int logicalIndex = getTopRowLogicalIndex() + i;
                setRowPosition(tr, 0, logicalIndex * getDefaultRowHeight());
            }

            /*
             * step 2: move scrollbar so that it corresponds to its previous
             * place
             */

            /*
             * This ratio needs to be calculated with the scrollsize (not max
             * scroll position) in order to align the top row with the new
             * scroll position.
             */
            double scrollRatio = verticalScrollbar.getScrollPos()
                    / verticalScrollbar.getScrollSize();
            scroller.recalculateScrollbarsForVirtualViewport();
            internalScrollEventCalls++;
            verticalScrollbar.setScrollPos((int) (getDefaultRowHeight()
                    * getRowCount() * scrollRatio));
            setBodyScrollPosition(horizontalScrollbar.getScrollPos(),
                    verticalScrollbar.getScrollPos());
            scroller.onScroll();

            /* step 3: make sure we have the correct amount of escalator rows. */
            verifyEscalatorCount();

            /*
             * TODO [[rowheight]] This simply doesn't work with variable rows
             * heights.
             */
            setTopRowLogicalIndex(getRowTop(visualRowOrder.getFirst())
                    / getDefaultRowHeight());

            Profiler.leave("Escalator.BodyRowContainer.reapplyDefaultRowHeights");
        }
    }

    private class ColumnConfigurationImpl implements ColumnConfiguration {
        public class Column {
            private static final int DEFAULT_COLUMN_WIDTH_PX = 100;

            private int definedWidth = -1;
            private int calculatedWidth = DEFAULT_COLUMN_WIDTH_PX;

            public void setWidth(int px) {
                definedWidth = px;
                calculatedWidth = (px >= 0) ? px : DEFAULT_COLUMN_WIDTH_PX;
            }

            public int getDefinedWidth() {
                return definedWidth;
            }

            public int getCalculatedWidth() {
                return calculatedWidth;
            }
        }

        private final List<Column> columns = new ArrayList<Column>();
        private int frozenColumns = 0;

        /**
         * A cached array of all the calculated column widths.
         * 
         * @see #getCalculatedColumnWidths()
         */
        private int[] widthsArray = null;

        /**
         * {@inheritDoc}
         * <p>
         * <em>Implementation detail:</em> This method does no DOM modifications
         * (i.e. is very cheap to call) if there are no rows in the DOM when
         * this method is called.
         * 
         * @see #hasSomethingInDom()
         */
        @Override
        public void removeColumns(final int index, final int numberOfColumns) {
            assertArgumentsAreValidAndWithinRange(index, numberOfColumns);

            flyweightRow.removeCells(index, numberOfColumns);

            // Cope with removing frozen columns
            if (index < frozenColumns) {
                if (index + numberOfColumns < frozenColumns) {
                    /*
                     * Last removed column was frozen, meaning that all removed
                     * columns were frozen. Just decrement the number of frozen
                     * columns accordingly.
                     */
                    frozenColumns -= numberOfColumns;
                } else {
                    /*
                     * If last removed column was not frozen, we have removed
                     * columns beyond the frozen range, so all remaining frozen
                     * columns are to the left of the removed columns.
                     */
                    frozenColumns = index;
                }
            }

            List<Column> removedColumns = new ArrayList<Column>();
            for (int i = 0; i < numberOfColumns; i++) {
                removedColumns.add(columns.remove(index));
            }

            if (hasSomethingInDom()) {
                for (final AbstractRowContainer rowContainer : rowContainers) {
                    rowContainer.paintRemoveColumns(index, numberOfColumns,
                            removedColumns);
                }
            }
        }

        /**
         * Calculate the width of a row, as the sum of columns' widths.
         * 
         * @return the width of a row, in pixels
         */
        public int calculateRowWidth() {
            return getCalculatedColumnsWidth(Range.between(0, getColumnCount()));
        }

        private void assertArgumentsAreValidAndWithinRange(final int index,
                final int numberOfColumns) {
            if (numberOfColumns < 1) {
                throw new IllegalArgumentException(
                        "Number of columns can't be less than 1 (was "
                                + numberOfColumns + ")");
            }

            if (index < 0 || index + numberOfColumns > getColumnCount()) {
                throw new IndexOutOfBoundsException("The given "
                        + "column range (" + index + ".."
                        + (index + numberOfColumns)
                        + ") was outside of the current "
                        + "number of columns (" + getColumnCount() + ")");
            }
        }

        /**
         * {@inheritDoc}
         * <p>
         * <em>Implementation detail:</em> This method does no DOM modifications
         * (i.e. is very cheap to call) if there is no data for rows when this
         * method is called.
         * 
         * @see #hasColumnAndRowData()
         */
        @Override
        public void insertColumns(final int index, final int numberOfColumns) {
            if (index < 0 || index > getColumnCount()) {
                throw new IndexOutOfBoundsException("The given index(" + index
                        + ") was outside of the current number of columns (0.."
                        + getColumnCount() + ")");
            }

            if (numberOfColumns < 1) {
                throw new IllegalArgumentException(
                        "Number of columns must be 1 or greater (was "
                                + numberOfColumns);
            }

            flyweightRow.addCells(index, numberOfColumns);

            for (int i = 0; i < numberOfColumns; i++) {
                columns.add(index, new Column());
            }

            // Either all or none of the new columns are frozen
            boolean frozen = index < frozenColumns;
            if (frozen) {
                frozenColumns += numberOfColumns;
            }

            if (hasColumnAndRowData()) {
                for (final AbstractRowContainer rowContainer : rowContainers) {
                    rowContainer.paintInsertColumns(index, numberOfColumns,
                            frozen);
                }
            }
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public void setFrozenColumnCount(int count)
                throws IllegalArgumentException {
            if (count < 0 || count > getColumnCount()) {
                throw new IllegalArgumentException(
                        "count must be between 0 and the current number of columns ("
                                + columns + ")");
            }
            int oldCount = frozenColumns;
            if (count == oldCount) {
                return;
            }

            frozenColumns = count;

            if (hasSomethingInDom()) {
                // Are we freezing or unfreezing?
                boolean frozen = count > oldCount;

                int firstAffectedCol;
                int firstUnaffectedCol;

                if (frozen) {
                    firstAffectedCol = oldCount;
                    firstUnaffectedCol = count;
                } else {
                    firstAffectedCol = count;
                    firstUnaffectedCol = oldCount;
                }

                for (int col = firstAffectedCol; col < firstUnaffectedCol; col++) {
                    header.setColumnFrozen(col, frozen);
                    body.setColumnFrozen(col, frozen);
                    footer.setColumnFrozen(col, frozen);
                }
            }

            scroller.recalculateScrollbarsForVirtualViewport();
        }

        @Override
        public int getFrozenColumnCount() {
            return frozenColumns;
        }

        @Override
        public void setColumnWidth(int index, int px)
                throws IllegalArgumentException {
            checkValidColumnIndex(index);

            columns.get(index).setWidth(px);
            widthsArray = null;

            /*
             * TODO [[optimize]]: only modify the elements that are actually
             * modified.
             */
            header.reapplyColumnWidths();
            body.reapplyColumnWidths();
            footer.reapplyColumnWidths();
            recalculateElementSizes();
        }

        private void checkValidColumnIndex(int index)
                throws IllegalArgumentException {
            if (!Range.withLength(0, getColumnCount()).contains(index)) {
                throw new IllegalArgumentException("The given column index ("
                        + index + ") does not exist");
            }
        }

        @Override
        public int getColumnWidth(int index) throws IllegalArgumentException {
            checkValidColumnIndex(index);
            return columns.get(index).getDefinedWidth();
        }

        @Override
        public int getColumnWidthActual(int index) {
            return columns.get(index).getCalculatedWidth();
        }

        /**
         * Calculates the width of the columns in a given range.
         * 
         * @param columns
         *            the columns to calculate
         * @return the total width of the columns in the given
         *         <code>columns</code>
         */
        int getCalculatedColumnsWidth(@SuppressWarnings("hiding")
        final Range columns) {
            /*
             * This is an assert instead of an exception, since this is an
             * internal method.
             */
            assert columns.isSubsetOf(Range.between(0, getColumnCount())) : "Range "
                    + "was outside of current column range (i.e.: "
                    + Range.between(0, getColumnCount())
                    + ", but was given :"
                    + columns;

            int sum = 0;
            for (int i = columns.getStart(); i < columns.getEnd(); i++) {
                sum += getColumnWidthActual(i);
            }
            return sum;
        }

        void setCalculatedColumnWidth(int index, int width) {
            columns.get(index).calculatedWidth = width;
            widthsArray = null;
        }

        int[] getCalculatedColumnWidths() {
            if (widthsArray == null || widthsArray.length != getColumnCount()) {
                widthsArray = new int[getColumnCount()];
                for (int i = 0; i < columns.size(); i++) {
                    widthsArray[i] = columns.get(i).getCalculatedWidth();
                }
            }
            return widthsArray;
        }
    }

    // abs(atan(y/x))*(180/PI) = n deg, x = 1, solve y
    /**
     * The solution to
     * <code>|tan<sup>-1</sup>(<i>x</i>)|&times;(180/&pi;)&nbsp;=&nbsp;30</code>
     * .
     * <p>
     * This constant is placed in the Escalator class, instead of an inner
     * class, since even mathematical expressions aren't allowed in non-static
     * inner classes for constants.
     */
    private static final double RATIO_OF_30_DEGREES = 1 / Math.sqrt(3);
    /**
     * The solution to
     * <code>|tan<sup>-1</sup>(<i>x</i>)|&times;(180/&pi;)&nbsp;=&nbsp;40</code>
     * .
     * <p>
     * This constant is placed in the Escalator class, instead of an inner
     * class, since even mathematical expressions aren't allowed in non-static
     * inner classes for constants.
     */
    private static final double RATIO_OF_40_DEGREES = Math.tan(2 * Math.PI / 9);

    private static final String DEFAULT_WIDTH = "400.0px";
    private static final String DEFAULT_HEIGHT = "400.0px";

    private FlyweightRow flyweightRow = new FlyweightRow(this);

    /** The {@code <thead/>} tag. */
    private final Element headElem = DOM.createTHead();
    /** The {@code <tbody/>} tag. */
    private final Element bodyElem = DOM.createTBody();
    /** The {@code <tfoot/>} tag. */
    private final Element footElem = DOM.createTFoot();

    /**
     * TODO: investigate whether this field is now unnecessary, as
     * {@link ScrollbarBundle} now caches its values.
     * 
     * @deprecated maybe...
     */
    @Deprecated
    private double tBodyScrollTop = 0;

    /**
     * TODO: investigate whether this field is now unnecessary, as
     * {@link ScrollbarBundle} now caches its values.
     * 
     * @deprecated maybe...
     */
    @Deprecated
    private double tBodyScrollLeft = 0;

    private final VerticalScrollbarBundle verticalScrollbar = new VerticalScrollbarBundle();
    private final HorizontalScrollbarBundle horizontalScrollbar = new HorizontalScrollbarBundle();

    private final HeaderRowContainer header = new HeaderRowContainer(headElem);
    private final BodyRowContainer body = new BodyRowContainer(bodyElem);
    private final FooterRowContainer footer = new FooterRowContainer(footElem);

    private final Scroller scroller = new Scroller();

    private final AbstractRowContainer[] rowContainers = new AbstractRowContainer[] {
            header, body, footer };

    private final ColumnConfigurationImpl columnConfiguration = new ColumnConfigurationImpl();
    private final Element tableWrapper;

    private PositionFunction position;

    private int internalScrollEventCalls = 0;

    /** The cached width of the escalator, in pixels. */
    private double widthOfEscalator;
    /** The cached height of the escalator, in pixels. */
    private double heightOfEscalator;

    /** The height of Escalator in terms of body rows. */
    private double heightByRows = GridState.DEFAULT_HEIGHT_BY_ROWS;

    /** The height of Escalator, as defined by {@link #setHeight(String)} */
    private String heightByCss = "";

    private HeightMode heightMode = HeightMode.CSS;

    private static native double getPreciseWidth(Element element)
    /*-{
        if (element.getBoundingClientRect) {
            var rect = element.getBoundingClientRect();
            return rect.right - rect.left;
        } else {
            return element.offsetWidth;
        }
    }-*/;

    private static native double getPreciseHeight(Element element)
    /*-{
        if (element.getBoundingClientRect) {
            var rect = element.getBoundingClientRect();
            return rect.bottom - rect.top;
        } else {
            return element.offsetHeight;
        }
    }-*/;

    /**
     * Creates a new Escalator widget instance.
     */
    public Escalator() {

        detectAndApplyPositionFunction();
        getLogger().info(
                "Using " + position.getClass().getSimpleName()
                        + " for position");

        final Element root = DOM.createDiv();
        setElement(root);

        root.appendChild(verticalScrollbar.getElement());
        verticalScrollbar.setScrollbarThickness(Util.getNativeScrollbarSize());

        root.appendChild(horizontalScrollbar.getElement());
        horizontalScrollbar
                .setScrollbarThickness(Util.getNativeScrollbarSize());
        horizontalScrollbar
                .addVisibilityHandler(new ScrollbarBundle.VisibilityHandler() {
                    @Override
                    public void visibilityChanged(
                            ScrollbarBundle.VisibilityChangeEvent event) {
                        /*
                         * We either lost or gained a scrollbar. In any case, we
                         * need to change the height, if it's defined by rows.
                         */
                        applyHeightByRows();
                    }
                });

        tableWrapper = DOM.createDiv();

        root.appendChild(tableWrapper);

        final Element table = DOM.createTable();
        tableWrapper.appendChild(table);

        table.appendChild(headElem);
        table.appendChild(bodyElem);
        table.appendChild(footElem);

        setStylePrimaryName("v-escalator");

        // init default dimensions
        setHeight(null);
        setWidth(null);
    }

    @Override
    protected void onLoad() {
        super.onLoad();

        header.autodetectRowHeight();
        body.autodetectRowHeight();
        footer.autodetectRowHeight();

        header.paintInsertRows(0, header.getRowCount());
        footer.paintInsertRows(0, footer.getRowCount());
        recalculateElementSizes();
        /*
         * Note: There's no need to explicitly insert rows into the body.
         * 
         * recalculateElementSizes will recalculate the height of the body. This
         * has the side-effect that as the body's size grows bigger (i.e. from 0
         * to its actual height), more escalator rows are populated. Those
         * escalator rows are then immediately rendered. This, in effect, is the
         * same thing as inserting those rows.
         * 
         * In fact, having an extra paintInsertRows here would lead to duplicate
         * rows.
         */

        scroller.attachScrollListener(verticalScrollbar.getElement());
        scroller.attachScrollListener(horizontalScrollbar.getElement());
        scroller.attachMousewheelListener(getElement());
        scroller.attachTouchListeners(getElement());
    }

    @Override
    protected void onUnload() {

        scroller.detachScrollListener(verticalScrollbar.getElement());
        scroller.detachScrollListener(horizontalScrollbar.getElement());
        scroller.detachMousewheelListener(getElement());
        scroller.detachTouchListeners(getElement());

        header.paintRemoveRows(0, header.getRowCount());
        footer.paintRemoveRows(0, footer.getRowCount());
        body.paintRemoveRows(0, body.getRowCount());

        super.onUnload();
    }

    private void detectAndApplyPositionFunction() {
        /*
         * firefox has a bug in its translate operation, showing white space
         * when adjusting the scrollbar in BodyRowContainer.paintInsertRows
         */
        if (Window.Navigator.getUserAgent().contains("Firefox")) {
            position = new AbsolutePosition();
            return;
        }

        final Style docStyle = Document.get().getBody().getStyle();
        if (hasProperty(docStyle, "transform")) {
            if (hasProperty(docStyle, "transformStyle")) {
                position = new Translate3DPosition();
            } else {
                position = new TranslatePosition();
            }
        } else if (hasProperty(docStyle, "webkitTransform")) {
            position = new WebkitTranslate3DPosition();
        } else {
            position = new AbsolutePosition();
        }
    }

    private Logger getLogger() {
        return Logger.getLogger(getClass().getName());
    }

    private static native boolean hasProperty(Style style, String name)
    /*-{
        return style[name] !== undefined;
    }-*/;

    /**
     * Check whether there are both columns and any row data (for either
     * headers, body or footer).
     * 
     * @return <code>true</code> iff header, body or footer has rows && there
     *         are columns
     */
    private boolean hasColumnAndRowData() {
        return (header.getRowCount() > 0 || body.getRowCount() > 0 || footer
                .getRowCount() > 0) && columnConfiguration.getColumnCount() > 0;
    }

    /**
     * Check whether there are any cells in the DOM.
     * 
     * @return <code>true</code> iff header, body or footer has any child
     *         elements
     */
    private boolean hasSomethingInDom() {
        return headElem.hasChildNodes() || bodyElem.hasChildNodes()
                || footElem.hasChildNodes();
    }

    /**
     * Returns the representation of this Escalator header.
     * 
     * @return the header. Never <code>null</code>
     */
    public RowContainer getHeader() {
        return header;
    }

    /**
     * Returns the representation of this Escalator body.
     * 
     * @return the body. Never <code>null</code>
     */
    public RowContainer getBody() {
        return body;
    }

    /**
     * Returns the representation of this Escalator footer.
     * 
     * @return the footer. Never <code>null</code>
     */
    public RowContainer getFooter() {
        return footer;
    }

    /**
     * Returns the configuration object for the columns in this Escalator.
     * 
     * @return the configuration object for the columns in this Escalator. Never
     *         <code>null</code>
     */
    public ColumnConfiguration getColumnConfiguration() {
        return columnConfiguration;
    }

    @Override
    public void setWidth(final String width) {
        super.setWidth(width != null && !width.isEmpty() ? width
                : DEFAULT_WIDTH);
        recalculateElementSizes();
    }

    /**
     * {@inheritDoc}
     * <p>
     * If Escalator is currently not in {@link HeightMode#CSS}, the given value
     * is remembered, and applied once the mode is applied.
     * 
     * @see #setHeightMode(HeightMode)
     */
    @Override
    public void setHeight(String height) {
        /*
         * TODO remove method once RequiresResize and the Vaadin layoutmanager
         * listening mechanisms are implemented
         */

        heightByCss = height;
        if (getHeightMode() == HeightMode.CSS) {
            setHeightInternal(height);
        }
    }

    private void setHeightInternal(final String height) {
        final int escalatorRowsBefore = body.visualRowOrder.size();

        super.setHeight(height != null && !height.isEmpty() ? height
                : DEFAULT_HEIGHT);
        recalculateElementSizes();

        if (escalatorRowsBefore != body.visualRowOrder.size()) {
            fireRowVisibilityChangeEvent();
        }
    }

    /**
     * Returns the vertical scroll offset. Note that this is not necessarily the
     * same as the scroll top in the DOM
     * 
     * @return the logical vertical scroll offset
     */
    public double getScrollTop() {
        return verticalScrollbar.getScrollPos();
    }

    /**
     * Sets the vertical scroll offset. Note that this is not necessarily the
     * same as the scroll top in the DOM
     * 
     * @param scrollTop
     *            the number of pixels to scroll vertically
     */
    public void setScrollTop(final double scrollTop) {
        verticalScrollbar.setScrollPos(scrollTop);
    }

    /**
     * Returns the logical horizontal scroll offset. Note that this is not
     * necessarily the same as the scroll left in the DOM.
     * 
     * @return the logical horizontal scroll offset
     */
    public double getScrollLeft() {
        return horizontalScrollbar.getScrollPos();
    }

    /**
     * Sets the logical horizontal scroll offset. Note that this is not
     * necessarily the same as the scroll left in the DOM.
     * 
     * @param scrollLeft
     *            the number of pixels to scroll horizontally
     */
    public void setScrollLeft(final double scrollLeft) {
        horizontalScrollbar.setScrollPos(scrollLeft);
    }

    /**
     * Scrolls the body horizontally so that the column at the given index is
     * visible and there is at least {@code padding} pixels to the given scroll
     * destination.
     * 
     * @param columnIndex
     *            the index of the column to scroll to
     * @param destination
     *            where the column should be aligned visually after scrolling
     * @param padding
     *            the number pixels to place between the scrolled-to column and
     *            the viewport edge.
     * @throws IndexOutOfBoundsException
     *             if {@code columnIndex} is not a valid index for an existing
     *             column
     * @throws IllegalArgumentException
     *             if {@code destination} is {@link ScrollDestination#MIDDLE}
     *             and padding is nonzero, because having a padding on a
     *             centered column is undefined behavior, or if the column is
     *             frozen
     */
    public void scrollToColumn(final int columnIndex,
            final ScrollDestination destination, final int padding)
            throws IndexOutOfBoundsException, IllegalArgumentException {
        if (destination == ScrollDestination.MIDDLE && padding != 0) {
            throw new IllegalArgumentException(
                    "You cannot have a padding with a MIDDLE destination");
        }
        verifyValidColumnIndex(columnIndex);

        if (columnIndex < columnConfiguration.frozenColumns) {
            throw new IllegalArgumentException("The given column index "
                    + columnIndex + " is frozen.");
        }

        scroller.scrollToColumn(columnIndex, destination, padding);
    }

    private void verifyValidColumnIndex(final int columnIndex)
            throws IndexOutOfBoundsException {
        if (columnIndex < 0
                || columnIndex >= columnConfiguration.getColumnCount()) {
            throw new IndexOutOfBoundsException("The given column index "
                    + columnIndex + " does not exist.");
        }
    }

    /**
     * Scrolls the body vertically so that the row at the given index is visible
     * and there is at least {@literal padding} pixels to the given scroll
     * destination.
     * 
     * @param rowIndex
     *            the index of the logical row to scroll to
     * @param destination
     *            where the row should be aligned visually after scrolling
     * @param padding
     *            the number pixels to place between the scrolled-to row and the
     *            viewport edge.
     * @throws IndexOutOfBoundsException
     *             if {@code rowIndex} is not a valid index for an existing row
     * @throws IllegalArgumentException
     *             if {@code destination} is {@link ScrollDestination#MIDDLE}
     *             and padding is nonzero, because having a padding on a
     *             centered row is undefined behavior
     */
    public void scrollToRow(final int rowIndex,
            final ScrollDestination destination, final int padding)
            throws IndexOutOfBoundsException, IllegalArgumentException {
        if (destination == ScrollDestination.MIDDLE && padding != 0) {
            throw new IllegalArgumentException(
                    "You cannot have a padding with a MIDDLE destination");
        }
        verifyValidRowIndex(rowIndex);

        scroller.scrollToRow(rowIndex, destination, padding);
    }

    private void verifyValidRowIndex(final int rowIndex) {
        if (rowIndex < 0 || rowIndex >= body.getRowCount()) {
            throw new IndexOutOfBoundsException("The given row index "
                    + rowIndex + " does not exist.");
        }
    }

    /**
     * Recalculates the dimensions for all elements that require manual
     * calculations. Also updates the dimension caches.
     * <p>
     * <em>Note:</em> This method has the <strong>side-effect</strong>
     * automatically makes sure that an appropriate amount of escalator rows are
     * present. So, if the body area grows, more <strong>escalator rows might be
     * inserted</strong>. Conversely, if the body area shrinks,
     * <strong>escalator rows might be removed</strong>.
     */
    private void recalculateElementSizes() {
        if (!isAttached()) {
            return;
        }

        Profiler.enter("Escalator.recalculateElementSizes");
        widthOfEscalator = getPreciseWidth(getElement());
        heightOfEscalator = getPreciseHeight(getElement());
        for (final AbstractRowContainer rowContainer : rowContainers) {
            rowContainer.recalculateSectionHeight();
        }

        scroller.recalculateScrollbarsForVirtualViewport();
        body.verifyEscalatorCount();
        Profiler.leave("Escalator.recalculateElementSizes");
    }

    /**
     * A routing method for {@link Scroller#onScroll()}.
     * <p>
     * This is a workaround for GWT and JSNI unable to properly handle inner
     * classes, so instead we call the outer class' method, which calls the
     * inner class' respective method.
     * <p>
     * Ideally, this method would not exist, and {@link Scroller#onScroll()}
     * would be called directly.
     */
    private void onScroll() {
        scroller.onScroll();
    }

    /**
     * Snap deltas of x and y to the major four axes (up, down, left, right)
     * with a threshold of a number of degrees from those axes.
     * 
     * @param deltaX
     *            the delta in the x axis
     * @param deltaY
     *            the delta in the y axis
     * @param thresholdRatio
     *            the threshold in ratio (0..1) between x and y for when to snap
     * @return a two-element array: <code>[snappedX, snappedY]</code>
     */
    private static double[] snapDeltas(final double deltaX,
            final double deltaY, final double thresholdRatio) {

        final double[] array = new double[2];
        if (deltaX != 0 && deltaY != 0) {
            final double aDeltaX = Math.abs(deltaX);
            final double aDeltaY = Math.abs(deltaY);
            final double yRatio = aDeltaY / aDeltaX;
            final double xRatio = aDeltaX / aDeltaY;

            array[0] = (xRatio < thresholdRatio) ? 0 : deltaX;
            array[1] = (yRatio < thresholdRatio) ? 0 : deltaY;
        } else {
            array[0] = deltaX;
            array[1] = deltaY;
        }

        return array;
    }

    /**
     * Adds an event handler that gets notified when the range of visible rows
     * changes e.g. because of scrolling.
     * 
     * @param rowVisibilityChangeHandler
     *            the event handler
     * @return a handler registration for the added handler
     */
    public HandlerRegistration addRowVisibilityChangeHandler(
            RowVisibilityChangeHandler rowVisibilityChangeHandler) {
        return addHandler(rowVisibilityChangeHandler,
                RowVisibilityChangeEvent.TYPE);
    }

    private void fireRowVisibilityChangeEvent() {
        if (!body.visualRowOrder.isEmpty()) {
            int visibleRangeStart = body.getLogicalRowIndex(body.visualRowOrder
                    .getFirst());
            int visibleRangeEnd = body.getLogicalRowIndex(body.visualRowOrder
                    .getLast()) + 1;

            int visibleRowCount = visibleRangeEnd - visibleRangeStart;

            fireEvent(new RowVisibilityChangeEvent(visibleRangeStart,
                    visibleRowCount));
        } else {
            fireEvent(new RowVisibilityChangeEvent(0, 0));
        }
    }

    /**
     * Accesses the package private method Widget#setParent()
     * 
     * @param widget
     *            The widget to access
     * @param parent
     *            The parent to set
     */
    static native final void setParent(Widget widget, Widget parent)
    /*-{
        widget.@com.google.gwt.user.client.ui.Widget::setParent(Lcom/google/gwt/user/client/ui/Widget;)(parent);
    }-*/;

    /**
     * Returns the widget from a cell node or <code>null</code> if there is no
     * widget in the cell
     * 
     * @param cellNode
     *            The cell node
     */
    static Widget getWidgetFromCell(Node cellNode) {
        Node possibleWidgetNode = cellNode.getFirstChild();
        if (possibleWidgetNode != null
                && possibleWidgetNode.getNodeType() == Node.ELEMENT_NODE) {
            @SuppressWarnings("deprecation")
            com.google.gwt.user.client.Element castElement = (com.google.gwt.user.client.Element) possibleWidgetNode
                    .cast();
            return Util.findWidget(castElement, null);
        }
        return null;
    }

    /**
     * Forces the escalator to recalculate the widths of its columns.
     * <p>
     * All columns that haven't been assigned an explicit width will be resized
     * to fit all currently visible contents.
     * 
     * @see ColumnConfiguration#setColumnWidth(int, int)
     */
    public void calculateColumnWidths() {
        boolean widthsHaveChanged = false;
        for (int colIndex = 0; colIndex < columnConfiguration.getColumnCount(); colIndex++) {
            if (columnConfiguration.getColumnWidth(colIndex) >= 0) {
                continue;
            }

            final int oldColumnWidth = columnConfiguration
                    .getColumnWidthActual(colIndex);

            int maxColumnWidth = 0;
            maxColumnWidth = Math.max(maxColumnWidth,
                    header.calculateMaxColWidth(colIndex));
            maxColumnWidth = Math.max(maxColumnWidth,
                    body.calculateMaxColWidth(colIndex));
            maxColumnWidth = Math.max(maxColumnWidth,
                    footer.calculateMaxColWidth(colIndex));

            Logger.getLogger("Escalator.calculateColumnWidths").info(
                    "#" + colIndex + ": " + maxColumnWidth + "px");

            if (oldColumnWidth != maxColumnWidth) {
                columnConfiguration.setCalculatedColumnWidth(colIndex,
                        maxColumnWidth);
                widthsHaveChanged = true;
            }
        }

        if (widthsHaveChanged) {
            header.reapplyColumnWidths();
            body.reapplyColumnWidths();
            footer.reapplyColumnWidths();
            recalculateElementSizes();
        }
    }

    @Override
    public void setStylePrimaryName(String style) {
        super.setStylePrimaryName(style);

        verticalScrollbar.setStylePrimaryName(style);
        horizontalScrollbar.setStylePrimaryName(style);

        UIObject.setStylePrimaryName(tableWrapper, style + "-tablewrapper");

        header.setStylePrimaryName(style);
        body.setStylePrimaryName(style);
        footer.setStylePrimaryName(style);
    }

    /**
     * Sets the number of rows that should be visible in Escalator's body, while
     * {@link #getHeightMode()} is {@link HeightMode#ROW}.
     * <p>
     * If Escalator is currently not in {@link HeightMode#ROW}, the given value
     * is remembered, and applied once the mode is applied.
     * 
     * @param rows
     *            the number of rows that should be visible in Escalator's body
     * @throws IllegalArgumentException
     *             if {@code rows} is zero or less
     * @throws IllegalArgumentException
     *             if {@code rows} is {@link Double#isInifinite(double)
     *             infinite}
     * @throws IllegalArgumentException
     *             if {@code rows} is {@link Double#isNaN(double) NaN}.
     * @see #setHeightMode(HeightMode)
     */
    public void setHeightByRows(double rows) throws IllegalArgumentException {
        if (rows <= 0) {
            throw new IllegalArgumentException(
                    "The number of rows must be a positive number.");
        } else if (Double.isInfinite(rows)) {
            throw new IllegalArgumentException(
                    "The number of rows must be finite.");
        } else if (Double.isNaN(rows)) {
            throw new IllegalArgumentException("The number must not be NaN.");
        }

        heightByRows = rows;
        applyHeightByRows();
    }

    /**
     * Gets the amount of rows in Escalator's body that are shown, while
     * {@link #getHeightMode()} is {@link HeightMode#ROW}.
     * <p>
     * By default, it is {@value GridState#DEFAULT_HEIGHT_BY_ROWS}.
     * 
     * @return the amount of rows that are being shown in Escalator's body
     * @see #setHeightByRows(double)
     */
    public double getHeightByRows() {
        return heightByRows;
    }

    /**
     * Reapplies the row-based height of the Grid, if Grid currently should
     * define its height that way.
     */
    private void applyHeightByRows() {
        if (heightMode != HeightMode.ROW) {
            return;
        }

        double headerHeight = header.heightOfSection;
        double footerHeight = footer.heightOfSection;
        double bodyHeight = body.getDefaultRowHeight() * heightByRows;
        double scrollbar = horizontalScrollbar.showsScrollHandle() ? horizontalScrollbar
                .getScrollbarThickness() : 0;

        double totalHeight = headerHeight + bodyHeight + scrollbar
                + footerHeight;
        setHeightInternal(totalHeight + "px");
    }

    /**
     * Defines the mode in which the Escalator widget's height is calculated.
     * <p>
     * If {@link HeightMode#CSS} is given, Escalator will respect the values
     * given via {@link #setHeight(String)}, and behave as a traditional Widget.
     * <p>
     * If {@link HeightMode#ROW} is given, Escalator will make sure that the
     * {@link #getBody() body} will display as many rows as
     * {@link #getHeightByRows()} defines. <em>Note:</em> If headers/footers are
     * inserted or removed, the widget will resize itself to still display the
     * required amount of rows in its body. It also takes the horizontal
     * scrollbar into account.
     * 
     * @param heightMode
     *            the mode in to which Escalator should be set
     */
    public void setHeightMode(HeightMode heightMode) {
        /*
         * This method is a workaround for the fact that Vaadin re-applies
         * widget dimensions (height/width) on each state change event. The
         * original design was to have setHeight an setHeightByRow be equals,
         * and whichever was called the latest was considered in effect.
         * 
         * But, because of Vaadin always calling setHeight on the widget, this
         * approach doesn't work.
         */

        if (heightMode != this.heightMode) {
            this.heightMode = heightMode;

            switch (this.heightMode) {
            case CSS:
                setHeight(heightByCss);
                break;
            case ROW:
                setHeightByRows(heightByRows);
                break;
            default:
                throw new IllegalStateException("Unimplemented feature "
                        + "- unknown HeightMode: " + this.heightMode);
            }
        }
    }

    /**
     * Returns the current {@link HeightMode} the Escalator is in.
     * <p>
     * Defaults to {@link HeightMode#CSS}.
     * 
     * @return the current HeightMode
     */
    public HeightMode getHeightMode() {
        return heightMode;
    }
}
