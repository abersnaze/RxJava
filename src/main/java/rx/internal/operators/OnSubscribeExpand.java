/**
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rx.internal.operators;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observable.Operator;
import rx.Subscriber;
import rx.exceptions.Exceptions;
import rx.functions.Func1;
import rx.internal.util.BackpressureDrainManager;

public final class OnSubscribeExpand<T> implements OnSubscribe<Observable<T>> {
    final Observable<T> source;
    final Func1<? super T, ? extends Observable<T>> func;
    final Func1<? super T, Boolean> shouldVisit;

    public OnSubscribeExpand(Observable<T> source, Func1<? super T, ? extends Observable<T>> func, Func1<? super T, Boolean> shouldVisit) {
        this.source = source;
        this.func = func;
        this.shouldVisit = shouldVisit;
    }

    @Override
    public void call(final Subscriber<? super Observable<T>> child) {
        ExpandOuterSubscriber<T> subscriber = new ExpandOuterSubscriber<T>(child, func, shouldVisit);
        child.setProducer(subscriber.manager);
        subscriber.active.incrementAndGet();
        subscriber.queue.offer(new ExpandSeedWrapper<T>(source));
        subscriber.manager.drain();
    }

    static final class ExpandSeedWrapper<T> {
        final Observable<T> source;

        public ExpandSeedWrapper(Observable<T> source) {
            this.source = source;
        }
    }

    static final class ExpandElementWrapper<T> {
        final T t;
        final Subscriber<? super T> out;

        public ExpandElementWrapper(T elm, Subscriber<? super T> out) {
            this.t = elm;
            this.out = out;
        }
    }

    static final class ExpandOuterSubscriber<T> implements BackpressureDrainManager.BackpressureQueueCallback {
        private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<Object>();
        private final Subscriber<? super Observable<T>> child;
        private final BackpressureDrainManager manager;
        private final AtomicLong active = new AtomicLong();
        private final Func1<? super T, ? extends Observable<T>> func;
        private final Func1<? super T, Boolean> shouldVisit;

        public ExpandOuterSubscriber(Subscriber<? super Observable<T>> child, Func1<? super T, ? extends Observable<T>> func, Func1<? super T, Boolean> shouldVisit) {
            this.child = child;
            this.func = func;
            this.manager = new BackpressureDrainManager(this);
            this.shouldVisit = shouldVisit;
        }

        @Override
        public Object peek() {
            return queue.peek();
        }

        @Override
        public Object poll() {
            return queue.poll();
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean accept(Object obj) {
            // get the first/next inner Observable
            Observable<T> inner;
            if (obj instanceof ExpandSeedWrapper) {
                // first Observable
                inner = ((ExpandSeedWrapper<T>) obj).source;
            } else if (NotificationLite.isCompleted(obj)) {
                child.onCompleted();
                return true;
            } else {
                // process an onNext from a different inner Observable
                ExpandElementWrapper<T> wrapper = (ExpandElementWrapper<T>) obj;
                // the value to onNext
                T t = wrapper.t;
                // the subscriber to the inner Observable
                Subscriber<? super T> out = wrapper.out;

                Boolean visit = null;
                try {
                    visit = shouldVisit == null ? Boolean.TRUE : shouldVisit.call(t);
                } catch (Throwable e) {
                    Exceptions.throwOrReport(e, out);
                    return true;
                }

                if (visit == Boolean.TRUE) {
                    out.onNext(t);

                    try {
                        inner = func.call(t);
                    } catch (Throwable e) {
                        Exceptions.throwOrReport(e, child);
                        return true;
                    }
                } else {
                    inner = Observable.empty();
                }
            }

            child.onNext(inner.lift(new ExpandMonitorOperator<T>(this)));
            return false;
        }

        @Override
        public void complete(Throwable exception) {
            if (active.decrementAndGet() == 0) {
                queue.offer(NotificationLite.completed());
                manager.terminateAndDrain();
            }
        }
    }

    static final class ExpandMonitorOperator<T> implements Operator<T, T> {
        private ExpandOuterSubscriber<T> parentSubscriber;

        public ExpandMonitorOperator(ExpandOuterSubscriber<T> parentSubscriber) {
            this.parentSubscriber = parentSubscriber;
        }

        @Override
        public Subscriber<? super T> call(Subscriber<? super T> childSubscriber) {
            return new ExpandInnerSubscriber<T>(childSubscriber, parentSubscriber);
        }
    }

    static final class ExpandInnerSubscriber<T> extends Subscriber<T> {
        private Subscriber<? super T> childSubscriber;
        private ExpandOuterSubscriber<T> parentSubscriber;

        public ExpandInnerSubscriber(Subscriber<? super T> childSubscriber, ExpandOuterSubscriber<T> parentSubscriber) {
            this.childSubscriber = childSubscriber;
            this.parentSubscriber = parentSubscriber;
        }

        @Override
        public void onCompleted() {
            childSubscriber.onCompleted();
            if (parentSubscriber.active.decrementAndGet() == 0) {
                parentSubscriber.queue.offer(NotificationLite.completed());
                parentSubscriber.manager.terminateAndDrain();
            }
        }

        @Override
        public void onError(Throwable e) {
            childSubscriber.onError(e);
            if (parentSubscriber.active.decrementAndGet() == 0) {
                parentSubscriber.queue.offer(NotificationLite.completed());
                parentSubscriber.manager.terminateAndDrain();
            }
        }

        @Override
        public void onNext(T t) {
            long x = parentSubscriber.active.incrementAndGet();
            parentSubscriber.queue.offer(new ExpandElementWrapper<T>(t, childSubscriber));
            parentSubscriber.manager.drain();
        }
    }
}
