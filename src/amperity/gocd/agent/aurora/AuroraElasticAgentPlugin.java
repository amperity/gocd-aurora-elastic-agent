package amperity.gocd.agent.aurora;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import com.thoughtworks.go.plugin.api.GoApplicationAccessor;
import com.thoughtworks.go.plugin.api.GoPluginIdentifier;
import com.thoughtworks.go.plugin.api.GoPlugin;
import com.thoughtworks.go.plugin.api.annotation.Extension;
import com.thoughtworks.go.plugin.api.exceptions.UnhandledRequestTypeException;
import com.thoughtworks.go.plugin.api.logging.Logger;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;
import java.util.Arrays;


@Extension
public class AuroraElasticAgentPlugin implements GoPlugin {

    public static final Logger LOG = Logger.getLoggerFor(AuroraElasticAgentPlugin.class);

    // Fields
    private IFn handler;
    private GoApplicationAccessor accessor;


    /**
     * The plugin identifier tells GoCD what kind of plugin this is and what
     * version(s) of the request/response API it supports.
     */
    @Override
    public GoPluginIdentifier pluginIdentifier() {
        return new GoPluginIdentifier("elastic-agent", Arrays.asList("5.0"));
    }


    /**
     * Executed once at startup to inject an application accessor.
     */
    @Override
    public void initializeGoApplicationAccessor(GoApplicationAccessor accessor) {
        LOG.info("Initializing plugin");
        try {
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Clojure.read("amperity.gocd.agent.aurora.plugin"));
            this.handler = Clojure.var("amperity.gocd.agent.aurora.plugin", "handler");
        } catch (Exception ex) {
            LOG.error("Failed to load plugin API handler", ex);
            throw ex;
        }
        this.accessor = accessor;
    }


    /**
     * Handle a plugin request and return a response.
     *
     * The response is very much like a HTTP response — it has a status code, a
     * response body and optional headers.
     */
    @Override
    public GoPluginApiResponse handle(GoPluginApiRequest request) throws UnhandledRequestTypeException {
        return (GoPluginApiResponse)this.handler.invoke(request);
    }

}
