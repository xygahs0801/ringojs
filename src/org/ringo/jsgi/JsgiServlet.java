/*
 *  Copyright 2009 Hannes Wallnoefer <hannes@helma.at>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.ringo.jsgi;

import org.ringo.tools.RingoConfiguration;
import org.ringo.tools.RingoRunner;
import org.ringo.repository.Repository;
import org.ringo.repository.FileRepository;
import org.ringo.repository.WebappRepository;
import org.ringo.engine.RhinoEngine;
import org.ringo.util.StringUtils;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.WrappedException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.File;
import java.util.Queue;

public class JsgiServlet extends HttpServlet {

    String module;
    Object function;
    RhinoEngine engine;

    public JsgiServlet() {}

    public JsgiServlet(RhinoEngine engine) throws ServletException {
        this(engine, null);
    }

    public JsgiServlet(RhinoEngine engine, Callable callable) throws ServletException {
        this.engine = engine;
        this.function = callable;
        try {
            engine.defineHostClass(JsgiEnv.class);
        } catch (Exception x) {
            throw new ServletException(x);
        }
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        // don't overwrite function if it was set in constructor
        if (function == null) {
            module = getInitParam(config, "moduleName", "config");
            function = getInitParam(config, "functionName", "app");
        }

        if (engine == null) {
            String ringoHome = getInitParam(config, "ringoHome", "WEB-INF");
            String modulePath = getInitParam(config, "modulePath", "app");

            Repository home = new WebappRepository(config.getServletContext(), ringoHome);
            try {
                if (!home.exists()) {
                    home = new FileRepository(ringoHome);
                }
                String[] paths = StringUtils.split(modulePath, File.pathSeparator);
                RingoConfiguration ringoConfig = new RingoConfiguration(home, paths, "modules");
                ringoConfig.setHostClasses(new Class[] { JsgiEnv.class });
                engine = new RhinoEngine(ringoConfig, null);
            } catch (Exception x) {
                throw new ServletException(x);
            }
        }
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            JsgiEnv env = new JsgiEnv(request, response);
            // Check for async JSGI response
            Queue asyncResponse = (Queue) request.getAttribute("_ringo_response");
            if (asyncResponse != null) {
                writeAsyncResponse(request, response, asyncResponse);
            } else {
                engine.invoke("ringo/jsgi", "handleRequest", module, function, env);
            }
        } catch (NoSuchMethodException x) {
            throw new ServletException(x);
        } catch (WrappedException x) {
            Throwable t = x.getWrappedException();
            // Rethrow jetty retry-request exceptions in order to support jetty continuations
            if ("org.mortbay.jetty.RetryRequest".equals(t.getClass().getName())) {
                throw (RuntimeException) t;
            }
            RingoRunner.reportError(t, System.err, false);
            throw(new ServletException(t));
        } catch (Exception x) {
            RingoRunner.reportError(x, System.err, false);
            throw(new ServletException(x));
        }
    }

    private void writeAsyncResponse(HttpServletRequest request,
                                    HttpServletResponse response,
                                    Queue queue)
            throws IOException, NoSuchMethodException {
        synchronized (queue) {
            Object part;
            while((part = queue.poll()) != null) {
                engine.invoke("ringo/jsgi", "writeAsync", request, response, part,
                        Boolean.valueOf(queue.isEmpty()));
            }
        }
    }

    private String getInitParam(ServletConfig config, String name, String defaultValue) {
        String value = config.getInitParameter(name);
        return value == null ? defaultValue : value;
    }

}