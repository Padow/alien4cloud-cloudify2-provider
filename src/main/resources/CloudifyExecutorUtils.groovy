import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import groovy.lang.Binding
import groovy.transform.Synchronized

import org.cloudifysource.utilitydomain.context.ServiceContextFactory

public class CloudifyExecutorUtils {
    
    static def RESEVED_ENV_KEYWORD = "NAME_VALUE_TO_PARSE"
    static def NAME_VALUE_SEPARATOR = "="
    static def counter = new AtomicInteger()
    static def threadPool = Executors.newFixedThreadPool(50, { r -> return new Thread(r as Runnable, "alien-executor-" + counter.incrementAndGet()) } as ThreadFactory )
    static def call = { c -> threadPool.submit(c as Callable) }

    static def executeScript(script, Map argsMap) {
        
        // Execute bash script
        def context = ServiceContextFactory.getServiceContext()

        def serviceDirectory = context.getServiceDirectory()
        println "service dir is: ${serviceDirectory}; script is: ${script}"
        def fullPathScript = "${serviceDirectory}/${script}";
        new AntBuilder().sequential {
            echo(message: "${fullPathScript} will be mark as executable...")
            chmod(file: "${fullPathScript}", perm:"+xr")
        }
        def scriptProcess = "${serviceDirectory}/${script}".execute(buildEnvForShOrBat(argsMap), null)
        def scriptErr = new StringBuffer()
        def scriptOut = new StringBuffer()
        scriptProcess.consumeProcessOutput(scriptOut, scriptErr)

        def scriptExitValue = scriptProcess.waitFor()
        
        print """
      ----------${script} : bash : Return Code ----------
      Return Code : $scriptExitValue
      ---------------------------------\n
      """

        if(scriptErr) {
            print """
      ---------- ${script} : bash :stderr ----------
      Return Code : $scriptExitValue
      $scriptErr
      ---------------------------------
      """
        }
        if(scriptOut) {
            print """
      ---------- ${script} : bash : stdout ----------
      Return Code : $scriptExitValue
      $scriptOut
      ---------------------------------
      """
        }

        if(scriptExitValue) {
            throw new RuntimeException("Error executing the script ${script} (return code: $scriptExitValue)")
        }
        
        return scriptOut;
    }
    
    
    /**
     * Execute a goovy script. Argument are passed to the groovy via the GroovyShell Binding, where they are injected as variables.
     * Consider to pass them as env vars
     * for a closure, we should not use the ServiceContextFactory as the context instance is already injected in the service file
     * Therefore, the caller script should have a defined "context" variable
     * */
    static def executeGroovy(context, groovyScript,  Map argsMap) {
        def serviceDirectory = context.getServiceDirectory()
        Binding binding = new Binding()
        
        //setting the args as variables
        buildEnvForGroovy(argsMap, binding)
        
        //set the context variable
        binding.setVariable("context", context)
        //hack for a MissingPropertyException thrown for CloudifyExecutorUtils
        binding.setVariable("CloudifyExecutorUtils", this)
        def shell = new GroovyShell(CloudifyExecutorUtils.class.classLoader,binding)
        return shell.evaluate(new File("${serviceDirectory}/${groovyScript}"))
    }

    static def executeParallel(groovyScripts, otherScripts) {
        def executionList = []
        println "$groovyScripts"
        for(script in groovyScripts) {
            println "parallel launch is $script"
            def theScript = script
            executionList.add(call{ executeGroovy(ServiceContextFactory.getServiceContext(), theScript, null) })
        }
        for(script in otherScripts) {
            def theScript = script
            executionList.add(call{ executeScript(theScript, null) })
        }
        for(execution in executionList) {
            execution.get();
        }
    }

    static def executeAsync(groovyScripts, otherScripts) {
        def executionList = []
        println "$groovyScripts"
        for(script in groovyScripts) {
            println "asynchronous launch is $script"
            def theScript = script
            executionList.add(call{ executeGroovy(ServiceContextFactory.getServiceContext(), theScript, null) })
        }
        for(script in otherScripts) {
            def theScript = script
            executionList.add(call{ executeScript(theScript, null) })
        }
    }

    static def fireEvent(nodeId, status) {
        def context = ServiceContextFactory.getServiceContext()

        // Fire event
        def application = context.getApplicationName()
        def instanceId = context.getInstanceId()
        println "THE INSTANCE ID is <${instanceId}>"
        CloudifyUtils.putEvent(application, nodeId, instanceId, status)
    }

    static def fireBlockStorageEvent(nodeId, event, volumeId) {
        def context = ServiceContextFactory.getServiceContext()

        // Fire event
        def application = context.getApplicationName()
        def instanceId = context.getInstanceId()
        println "THE INSTANCE ID is <${instanceId}>"
        CloudifyUtils.putBlockStorageEvent(application, nodeId, instanceId, event, volumeId)
    }

    static def waitFor(cloudifyService, nodeId, status) {
        CloudifyUtils.waitFor(cloudifyService, nodeId, status)
    }

    static def shutdown() {
        println "Shutting down threadpool"
        threadPool.shutdownNow();
        println "threadpool shut down!"
    }
    
    /**
     * build env vars given a map for sh scripts.
     * @param argsMap
     * @return
     */
    private static String[] buildEnvForShOrBat(Map argsMap) {
        if(argsMap != null && !argsMap.isEmpty()) {
            def merged = [:]
            merged.putAll(System.getenv())
            //parse a Name=Value
            parseNameValueIfExist(argsMap)
            merged.putAll(argsMap)
            return merged.collect { k, v -> v=="null"? "$k=''": "$k=$v" }
        }
        return null
    }
    
    /**
     * build env vars given a map for groovy scripts.
     * @param argsMap
     * @param binding
     * @return
     */
    private static def buildEnvForGroovy(Map argsMap, Binding binding) {
        if(argsMap != null && !argsMap.isEmpty()) {
            parseNameValueIfExist(argsMap)
            argsMap.each {  k, v ->
                if(v=="null") {
                    binding.setVariable(k, null);
                }else {
                    binding.setVariable(k, v);
                }
            }
        }
    }
    
    private static def parseNameValueIfExist(Map argsMap) {
        if(argsMap.containsKey(RESEVED_ENV_KEYWORD)) {
            def parsed = [:];
            def toParse = argsMap[RESEVED_ENV_KEYWORD];
            if(toParse) {
                toParse.each {
                def index = it.indexOf(NAME_VALUE_SEPARATOR)
                    if(index >= 0) {
                        def key = it.substring(0, index)
                        parsed.put(key, it.substring(key.size()+1,it.size()))
                    }
                }
                argsMap.remove(RESEVED_ENV_KEYWORD)
                argsMap.putAll(parsed)
            }
        }
    }
}