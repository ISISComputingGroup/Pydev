package org.python.pydev.ast.codecompletion.shell;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.python.copiedfromeclipsesrc.JDTNotAvailableException;
import org.python.pydev.ast.codecompletion.revisited.ModulesManager;
import org.python.pydev.ast.interpreter_managers.InterpreterManagersAPI;
import org.python.pydev.core.IInterpreterInfo;
import org.python.pydev.core.IInterpreterManager;
import org.python.pydev.core.IPythonNature;
import org.python.pydev.core.MisconfigurationException;
import org.python.pydev.core.PythonNatureWithoutProjectException;
import org.python.pydev.core.ShellId;
import org.python.pydev.core.log.Log;
import org.python.pydev.core.logging.DebugSettings;

public class ShellsContainer {

    /**
     * Reference to 'global python shells'
     *
     * this works as follows:
     * we have the interpreter as that the shell is related to as the 1st key
     *
     * and then we have the id with the shell type that points to the actual shell
     *
     * @see #MAIN_THREAD_SHELL
     * @see #OTHER_THREADS_SHELL
     */
    private static Map<String, Map<ShellId, AbstractShell>> shells = new HashMap<>();

    /**
     * simple stop of a shell (it may be later restarted)
     */
    public static void stopServerShell(IInterpreterInfo interpreter, ShellId id) {
        synchronized (shells) {
            Map<ShellId, AbstractShell> typeToShell = getTypeToShellFromId(interpreter);
            AbstractShell pythonShell = typeToShell.get(id);

            if (pythonShell != null) {
                try {
                    pythonShell.endIt();
                } catch (Exception e) {
                    // ignore... we are ending it anyway...
                }
            }
            typeToShell.remove(id); //there's no exception if it was not there in the 1st place...
        }
    }

    /**
     * Stops all registered shells (should only be called at plugin shutdown).
     */
    public static void shutdownAllShells() {
        synchronized (shells) {
            if (DebugSettings.DEBUG_CODE_COMPLETION) {
                org.python.pydev.shared_core.log.ToLogFile.toLogFile("Shutting down all shells (for good)...",
                        AbstractShell.class);
            }

            for (Iterator<Map<ShellId, AbstractShell>> iter = shells.values().iterator(); iter.hasNext();) {
                AbstractShell.finishedForGood = true; //we may no longer restart shells

                Map<ShellId, AbstractShell> rel = iter.next();
                if (rel != null) {
                    for (Iterator<AbstractShell> iter2 = rel.values().iterator(); iter2.hasNext();) {
                        AbstractShell element = iter2.next();
                        if (element != null) {
                            try {
                                element.shutdown(); //shutdown
                            } catch (Exception e) {
                                Log.log(e); //let's log it... this should not happen
                            }
                        }
                    }
                }
            }
            shells.clear();
        }
    }

    /**
     * Restarts all the shells and clears any related cache.
     *
     * @return an error message if some exception happens in this process (an empty string means all went smoothly).
     */
    public static String restartAllShells() {
        String ret = "";
        synchronized (shells) {
            try {
                if (DebugSettings.DEBUG_CODE_COMPLETION) {
                    org.python.pydev.shared_core.log.ToLogFile.toLogFile("Restarting all shells and clearing caches...",
                            AbstractShell.class);
                }

                for (Map<ShellId, AbstractShell> val : shells.values()) {
                    for (AbstractShell val2 : val.values()) {
                        if (val2 != null) {
                            val2.endIt();
                        }
                    }
                    IInterpreterManager[] interpreterManagers = InterpreterManagersAPI.getAllInterpreterManagers();
                    for (IInterpreterManager iInterpreterManager : interpreterManagers) {
                        if (iInterpreterManager == null) {
                            continue; //Should happen only on testing...
                        }
                        try {
                            iInterpreterManager.clearCaches();
                        } catch (Exception e) {
                            Log.log(e);
                            ret += e.getMessage() + "\n";
                        }
                    }
                    //Clear the global modules cache!
                    ModulesManager.clearCache();
                }
            } catch (Exception e) {
                Log.log(e);
                ret += e.getMessage() + "\n";
            }
        }
        return ret;
    }

    /**
     * @param interpreter the interpreter whose shell we want.
     * @return a map with the type of the shell mapping to the shell itself
     */
    private static Map<ShellId, AbstractShell> getTypeToShellFromId(IInterpreterInfo interpreter) {
        synchronized (shells) {
            Map<ShellId, AbstractShell> typeToShell = shells.get(interpreter.getExecutableOrJar());

            if (typeToShell == null) {
                typeToShell = new HashMap<>();
                shells.put(interpreter.getExecutableOrJar(), typeToShell);
            }
            return typeToShell;
        }
    }

    /**
     * register a shell and give it an id
     *
     * @param nature the nature (which has the information on the interpreter we want to used)
     * @param id the shell id
     * @see #MAIN_THREAD_SHELL
     * @see #OTHER_THREADS_SHELL
     *
     * @param shell the shell to register
     */
    public static void putServerShell(IPythonNature nature, ShellId id, AbstractShell shell) {
        synchronized (shells) {
            try {
                Map<ShellId, AbstractShell> typeToShell = getTypeToShellFromId(nature.getProjectInterpreter());
                typeToShell.put(id, shell);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static AbstractShell getServerShell(IPythonNature nature, ShellId id) throws IOException,
            JDTNotAvailableException, CoreException, MisconfigurationException, PythonNatureWithoutProjectException {
        return getServerShell(nature.getProjectInterpreter(), nature.getInterpreterType(), id);
    }

    /**
     * @param interpreter the interpreter that should create the shell
     *
     * @param relatedTo identifies to which kind of interpreter the shell should be related.
     * @see org.python.pydev.core.IPythonNature#INTERPRETER_TYPE_PYTHON
     * @see org.python.pydev.core.IPythonNature#INTERPRETER_TYPE_JYTHON
     *
     * @param a given id for the shell
     * @see #MAIN_THREAD_SHELL
     * @see #OTHER_THREADS_SHELL
     *
     * @return the shell with the given id related to some nature
     *
     * @throws CoreException
     * @throws IOException
     * @throws MisconfigurationException
     */
    private static AbstractShell getServerShell(IInterpreterInfo interpreter, int relatedTo, ShellId id)
            throws IOException, JDTNotAvailableException, CoreException, MisconfigurationException {
        AbstractShell pythonShell = null;
        synchronized (shells) {
            if (DebugSettings.DEBUG_CODE_COMPLETION) {
                org.python.pydev.shared_core.log.ToLogFile.toLogFile("Synchronizing on shells...", AbstractShell.class);
            }
            if (DebugSettings.DEBUG_CODE_COMPLETION) {
                String flavor;
                switch (relatedTo) {
                    case IPythonNature.INTERPRETER_TYPE_JYTHON:
                        flavor = "Jython";
                        break;
                    case IPythonNature.INTERPRETER_TYPE_IRONPYTHON:
                        flavor = "IronPython";
                        break;
                    default:
                        flavor = "Python";
                }
                ;
                org.python.pydev.shared_core.log.ToLogFile.toLogFile(
                        "Getting shell related to:" + flavor + " id:" + id + " interpreter: "
                                + interpreter.getExecutableOrJar(),
                        AbstractShell.class);
            }
            Map<ShellId, AbstractShell> typeToShell = getTypeToShellFromId(interpreter);
            pythonShell = typeToShell.get(id);

            if (pythonShell == null) {
                if (DebugSettings.DEBUG_CODE_COMPLETION) {
                    org.python.pydev.shared_core.log.ToLogFile.toLogFile("pythonShell == null", AbstractShell.class);
                }
                if (id == ShellId.CYTHON_MAIN_THREAD_SHELL || id == ShellId.CYTHON_OTHER_THREADS_SHELL) {
                    pythonShell = new CythonShell();
                } else {
                    if (relatedTo == IPythonNature.INTERPRETER_TYPE_PYTHON) {
                        pythonShell = new PythonShell();

                    } else if (relatedTo == IPythonNature.INTERPRETER_TYPE_JYTHON) {
                        pythonShell = new JythonShell();

                    } else if (relatedTo == IPythonNature.INTERPRETER_TYPE_IRONPYTHON) {
                        pythonShell = new IronpythonShell();

                    } else {
                        throw new RuntimeException("unknown related id");
                    }
                }
                if (DebugSettings.DEBUG_CODE_COMPLETION) {
                    org.python.pydev.shared_core.log.ToLogFile.toLogFile("pythonShell.startIt()", AbstractShell.class);
                    org.python.pydev.shared_core.log.ToLogFile.addLogLevel();
                }
                pythonShell.startIt(interpreter); //first start it
                if (DebugSettings.DEBUG_CODE_COMPLETION) {
                    org.python.pydev.shared_core.log.ToLogFile.remLogLevel();
                    org.python.pydev.shared_core.log.ToLogFile.toLogFile("Finished pythonShell.startIt()",
                            AbstractShell.class);
                }

                //then make it accessible
                typeToShell.put(id, pythonShell);
            }

        }
        return pythonShell;
    }

}
