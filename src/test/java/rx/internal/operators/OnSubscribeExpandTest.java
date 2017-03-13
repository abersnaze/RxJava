package rx.internal.operators;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

import rx.Observable;
import rx.functions.Func1;

public class OnSubscribeExpandTest {
    @Test
    public void test() {
        Observable<Integer> roots = Observable.just(5);

        Func1<Integer, Observable<Integer>> func = new Func1<Integer, Observable<Integer>>() {
            @Override
            public Observable<Integer> call(Integer n) {
                // process the value and return an observable of values to look at next.
                return Observable.range(1, n - 1);
            }
        };

        Observable<Observable<Integer>> expand = roots.expand(func);

        List<Integer> output = Observable.merge(expand).toList().toBlocking().first();
        assertEquals(Arrays.asList(5, 1, 2, 3, 4, 1, 1, 2, 1, 2, 3, 1, 1, 1, 2, 1), output);
    }

    @Test
    public void testUnique() {
        Observable<Integer> roots = Observable.just(5);

        Func1<Integer, Observable<Integer>> func = new Func1<Integer, Observable<Integer>>() {
            @Override
            public Observable<Integer> call(Integer n) {
                // process the value and return an observable of values to look at next.
                return Observable.range(1, n - 1);
            }
        };

        final HashSet<Integer> visited = new HashSet<Integer>();

        Func1<Integer, Boolean> shouldVisit = new Func1<Integer, Boolean>() {
            @Override
            public Boolean call(Integer next) {
                return visited.add(next);
            }
        };

        Observable<Observable<Integer>> expand = roots.expand(func, shouldVisit);

        List<Integer> output = Observable.merge(expand).toList().toBlocking().first();
        assertEquals(Arrays.asList(5, 1, 2, 3, 4), output);
    }
}
