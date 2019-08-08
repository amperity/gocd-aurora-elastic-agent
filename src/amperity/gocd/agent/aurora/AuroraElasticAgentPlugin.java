package amperity.gocd.agent.aurora;

import clojure.java.api.Clojure;
import clojure.lang.Atom;
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

    public static final Logger LOGGER = Logger.getLoggerFor(AuroraElasticAgentPlugin.class);

    // Fields
    private GoApplicationAccessor accessor;
    private IFn handler;
    private Atom state;


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
        LOGGER.info("Initializing plugin");
        this.accessor = accessor;

        IFn require = Clojure.var("clojure.core", "require");

        try {
            require.invoke(Clojure.read("amperity.gocd.agent.aurora.plugin"));
            this.handler = Clojure.var("amperity.gocd.agent.aurora.plugin", "handler");
        } catch (Exception ex) {
            LOGGER.error("Failed to load plugin API handler", ex);
            throw ex;
        }

        try {
            IFn atom = Clojure.var("clojure.core", "atom");
            this.state = (Atom)atom.invoke(Clojure.read("{:clients {}, :clusters {}, :agents {}}"));
        } catch (Exception ex) {
            LOGGER.error("Failed to create plugin state atom", ex);
            throw ex;
        }
    }


    /**
     * Handle a plugin request and return a response.
     *
     * The response is very much like a HTTP response — it has a status code, a
     * response body and optional headers.
     */
    @Override
    public GoPluginApiResponse handle(GoPluginApiRequest request) throws UnhandledRequestTypeException {
        return (GoPluginApiResponse)this.handler.invoke(state, request);
    }

}
