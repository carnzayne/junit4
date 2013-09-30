package org.junit.internal.runners.statements;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.Statement;

public class FailOnTimeout extends Statement {
    private final Statement fOriginalStatement;
    private final TimeUnit fTimeUnit;
    private final long fTimeout;
    private ThreadGroup fThreadGroup = null;

    public FailOnTimeout(Statement originalStatement, long millis) {
        this(originalStatement, millis, TimeUnit.MILLISECONDS);
    }

    public FailOnTimeout(Statement originalStatement, long timeout, TimeUnit unit) {
        fOriginalStatement = originalStatement;
        fTimeout = timeout;
        fTimeUnit = unit;
    }

    @Override
    public void evaluate() throws Throwable {
        FutureTask<Throwable> task = new FutureTask<Throwable>(new CallableStatement());
		fThreadGroup = new ThreadGroup ("FailOnTimeoutGroup");
        Thread thread = new Thread(fThreadGroup, task, "Time-limited test");
        thread.setDaemon(true);
        thread.start();
        Throwable throwable = getResult(task, thread);
        if (throwable != null) {
        	throw throwable;
        }
    }

    /**
     * Wait for the test task, returning the exception thrown by the test if the
     * test failed, an exception indicating a timeout if the test timed out, or
     * {@code null} if the test passed.
     */
    private Throwable getResult(FutureTask<Throwable> task, Thread thread) {
        try {
            return task.get(fTimeout, fTimeUnit);
        } catch (InterruptedException e) {
            return e; // caller will re-throw; no need to call Thread.interrupt()
        } catch (ExecutionException e) {
            // test failed; have caller re-throw the exception thrown by the test
            return e.getCause();
        } catch (TimeoutException e) {
            return createTimeoutException(thread);
        }
    }

    private Exception createTimeoutException(Thread thread) {
        StackTraceElement[] stackTrace = thread.getStackTrace();
        final Thread stuckThread = getStuckThread (thread);
        Exception exception = new Exception(String.format(
                "test timed out after %d %s", fTimeout, fTimeUnit.name().toLowerCase()));
        if (stuckThread != null) {
            Exception stuckThreadException = 
                new Exception ("Appears to be stuck in thread " +
                               stuckThread.getName());
            StackTraceElement[] threadStack;
            try {
                threadStack = stuckThread.getStackTrace();
            } catch (SecurityException e) {
                threadStack = new StackTraceElement[0];
            }
            stuckThreadException.setStackTrace (threadStack);
            exception = new MultipleFailureException    
                (Arrays.<Throwable>asList (exception, stuckThreadException));
        }
        if (stackTrace != null) {
            exception.setStackTrace(stackTrace);
            thread.interrupt();
        }
        return exception;
    }

    /**
     * Determines whether the test appears to be stuck in some thread other than
     * the "main thread" (the one created to run the test).
     * @param mainThread The main thread created by {@code evaluate()}
     * @return The thread which appears to be causing the problem, if different from
     * {@code mainThread}, or {@code null} if the main thread appears to be the
     * problem or if the thread cannot be determined.  The return value is never equal 
     * to {@code mainThread}.
     */
    private Thread getStuckThread (Thread mainThread) {
    	if (fThreadGroup == null) return null;
    	final int count = fThreadGroup.activeCount(); // this is just an estimate
    	int enumSize = Math.max (count * 2, 100);
    	int enumCount;
    	Thread[] threads;
		int loopCount = 0;
    	while (true) {
    		threads = new Thread[enumSize];
    		enumCount = fThreadGroup.enumerate (threads);
    		if (enumCount < enumSize) break;
            // if there are too many threads to fit into the array, enumerate's result
            // is >= the array's length; therefore we can't trust that it returned all
            // the threads.  Try again.
    		enumSize += 100;
    		if (++loopCount >= 5) return null;
    		// threads are proliferating too fast for us.  Bail before we get into 
    		// trouble.
    	} 
    	
    	// Now that we have all the threads in the test's thread group: Assume that
    	// any thread we're "stuck" in is RUNNABLE.  Look for all RUNNABLE threads. 
    	// If just one, we return that (unless it equals threadMain).  If there's more
    	// than one, pick the one that's using the most CPU time, if this feature is
    	// supported.
    	Thread stuckThread = null;
    	long maxCpuTime = 0;
    	for (int i = 0; i < enumCount; i++) {
    		if (threads[i].getState() == Thread.State.RUNNABLE) {
                long threadCpuTime = cpuTime (threads [i]);
                if (stuckThread == null || threadCpuTime > maxCpuTime) {
                    stuckThread = threads[i];
                    maxCpuTime = threadCpuTime;
                }
    		}   			
    	}
    	return (stuckThread == mainThread) ? null : stuckThread;
    }

    /**
     * Returns the CPU time used by a thread, if possible.
     * @param thr The thread to query.
     * @return The CPU time used by {@code thr}, or 0 if it cannot be determined.
     */
    private long cpuTime (Thread thr) {
		ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
        if (mxBean.isThreadCpuTimeSupported()) {
            try {
                return mxBean.getThreadCpuTime(thr.getId());
            } catch (UnsupportedOperationException e) {
            }
        }
        return 0;
    }

	private class CallableStatement implements Callable<Throwable> {
        public Throwable call() throws Exception {
            try {
                fOriginalStatement.evaluate();
            } catch (Exception e) {
                throw e;
            } catch (Throwable e) {
                return e;
            }
            return null;
        }
    }
}
