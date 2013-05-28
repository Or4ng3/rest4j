/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rest4j.impl;

import com.rest4j.API;
import com.rest4j.*;
import com.rest4j.ObjectFactory;
import com.rest4j.impl.model.*;
import com.rest4j.impl.model.ContentType;
import com.rest4j.impl.model.Error;
import com.rest4j.type.ApiType;
import com.rest4j.type.SimpleApiType;
import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @author Joseph Kapizza <joseph@rest4j.com>
 */
public class APIImpl implements API {
	final com.rest4j.impl.model.API root;
	final String pathPrefix;
	final Marshaller marshaller;
	final List<EndpointMapping> endpoints = new ArrayList<EndpointMapping>();
	final ResourceFactory resourceFactory;
	final APIParams params;

	public APIImpl(com.rest4j.impl.model.API root, String pathPrefix, ServiceProvider serviceProvider) throws ConfigurationException {
		this(root, pathPrefix, serviceProvider, new ObjectFactory[0]);
	}

	public APIImpl(com.rest4j.impl.model.API root, String pathPrefix, ServiceProvider serviceProvider, ObjectFactory[] factories) throws ConfigurationException {
		this.pathPrefix = pathPrefix;
		this.root = root;
		if (root.getParams() == null) this.params = new APIParams();
		else this.params = root.getParams();

		// configure and create marshaller
		List<Marshaller.ModelConfig> modelConfig = new ArrayList<Marshaller.ModelConfig>();
		for (Object child: root.getEndpointAndModel()) {
			if (child instanceof Model) {
				Model model = (Model) child;
				Object customMapper = serviceProvider.lookupFieldMapper(model.getName(), model.getFieldMapper());
				modelConfig.add(new Marshaller.ModelConfig(model, customMapper));
			}
		}
		marshaller = new Marshaller(modelConfig, factories);

		// create resourceFactory
		resourceFactory = new ResourceFactory(marshaller);

		// create endpoint mappings
		for (Object child: root.getEndpointAndModel()) {
			if (child instanceof Endpoint) {
				Endpoint endpoint = (Endpoint)child;
				endpoints.add(new EndpointMapping(endpoint, serviceProvider));
			}
		}
	}

	class APIExceptionWrapper extends ApiException {
		APIRequest request;
		ApiException ex;

		APIExceptionWrapper(APIRequest request, ApiException ex) {
			super(ex.getMessage());
			setHttpStatus(ex.getHttpStatus());
			this.request = request;
			this.ex = ex;
		}

		@Override
		public int getHttpStatus() {
			return ex.getHttpStatus();
		}

		@Override
		public JSONObject getJSONResponse() {
			return ex.getJSONResponse();
		}

		@Override
		public ApiException replaceMessage(String newMessage) {
			throw new IllegalStateException();
		}

		@Override
		public APIResponse createResponse() {
			JSONObject jsonResponse = getJSONResponse();
			return new ApiResponseImpl(APIImpl.this, request, jsonResponse == null ? null : new JSONResource(jsonResponse))
					.setStatus(getHttpStatus(), getMessage())
					.addHeader("Cache-control", "must-revalidate,no-cache,no-store");
		}

		@Override
		public String getHeader(String name) {
			return ex.getHeader(name);
		}
	}


	@Override
	public APIResponse serve(APIRequest request) throws IOException, ApiException {
		try {
			return serveInt(request);
		} catch (ApiException ex) {
			// wrap exceptions and implements createResponse()
			throw new APIExceptionWrapper(request, ex);
		}
	}

	APIResponse serveInt(APIRequest request) throws IOException, ApiException {

		if (!request.path().startsWith(pathPrefix)) {
			throw new ApiException("Wrong path: " + request.path() + ", does not match the path prefix '" + pathPrefix + "'").setHttpStatus(404);
		}
		if (request.method().equals("OPTIONS")) {
			try {
				findEndpoint(request);
			} catch (ApiException apiex) {
				if (apiex.getHttpStatus() != 405) {
					throw apiex;
				}
			}
			// enable cross-domain queries
			return new ApiResponseImpl(this, request, null)
					.addHeader("Access-Control-Allow-Origin", "*")
					.addHeader("Access-Control-Allow-Methods", getAllowedMethodsString(request))
					.addHeader("Access-Control-Max-Age", "10000000");
		}
		EndpointMapping endpoint = findEndpoint(request);

		if (endpoint.httpsonly && !request.https()) {
			throw new ApiException( "This request can only be sent over HTTPS.");
		}
		Object getResult = null;
		if (!request.method().equals("GET")) {
			APIRequest get = changeMethod(request, "GET");
			EndpointMapping getEndpoint = findEndpoint(get);
			if (request.header("If-Match") != null || endpoint.isPatch()) {
				// first perform GET, then decide if we should change the resource
				getResult = getEndpoint.invokeRaw(request, null);
			}
			if (request.header("If-Match") != null) {
				Resource getResource = resourceFactory.createResourceFrom(getResult, getEndpoint.getResponseContentType());
				if (!parseList(request.header("If-Match")).contains(getResource.getETag())) {
					throw new ApiException("Precondition failed").setHttpStatus(412);
				}
			}
		}
		Resource result = endpoint.invoke(request, getResult);
		if (request.header("If-None-Match") != null && request.method().equals("GET") && result != null) {
			String etag = result.getETag();
			if (parseList(request.header("If-None-Match")).contains(etag)) {
				throw new ApiException("Not modified").setHttpStatus(304).addHeader("ETag", etag);
			}
		}
		return new ApiResponseImpl(this, request, result).addHeader("Vary", "Accept-Encoding");
	}

	APIParams getParams() {
		return params;
	}

	interface ArgHandler {
		Object get(APIRequest request, Object getResponse, Params params) throws IOException, ApiException;
	}

	class EndpointMapping {
		Endpoint endpoint;
		StringWithParamsMatcher pathMatcher;
		String httpMethod;
		Object service;
		Method method;
		ArgHandler[] args;
		public boolean httpsonly;
		private boolean patch;

		EndpointMapping(Endpoint ep, ServiceProvider serviceProvider) throws ConfigurationException {
			endpoint = ep;
			pathMatcher = new StringWithParamsMatcher(ep.getRoute());
			httpMethod = ep.getHttp().name();
			service = serviceProvider.lookupService(ep.getService().getName());
			httpsonly = ep.isHttpsonly();
			patch = ep.getBody() != null && ep.getBody().getPatch() != null;
			if (service == null) {
				throw new ConfigurationException("No service with name "+ep.getService().getName());
			}
			String method = ep.getService().getMethod();
			if (method == null) {
				method = httpMethod.toLowerCase();
			}
			for (Method m: service.getClass().getMethods()) {
				if ((m.getModifiers()& Modifier.STATIC) != 0 || !m.getName().equals(method)) continue;
				this.method = m;
			}
			if (this.method == null) {
				throw new ConfigurationException("Cannot find non-static method with name "+method+" in "+ service.getClass());
			}

			int argCount = this.method.getParameterTypes().length;
			String[] paramNames;
			try {
				paramNames = Util.getParameterNames(service.getClass(), method);
			} catch (IOException e) {
				throw new ConfigurationException("Cannot parse class "+ service.getClass()+": "+e.getMessage());
			}
			assert paramNames.length == argCount;

			Type[] paramTypes = this.method.getGenericParameterTypes();
			boolean bodyParamFound = false;
			boolean paramsParamFound = false;
			args = new ArgHandler[argCount];
			for (int i=0; i<argCount; i++) {
				final String name = paramNames[i];
				Parameter param = null;
				for (Parameter x : ep.getParameters().getParameter()) {
					if (!name.equals(x.getName())) continue;
					param = x;
					break;
				}
				final Type paramType = paramTypes[i];
				if (param == null) {
					final FieldMapping fieldMapping;
					// try 'params' param
					if (paramType == Params.class) {
						if (paramsParamFound) {
							throw new ConfigurationException("Cannot be two Params arguments in method "+this.method);
						}
						paramsParamFound = true;
						args[i] = new ArgHandler() {
							@Override
							public Object get(APIRequest request, Object getResponse, Params params) {
								return params;
							}
						};
					} else if ((fieldMapping = checkFieldAsArgument(name, paramType)) != null) {
						args[i] = new ArgHandler() {
							@Override
							public Object get(APIRequest request, Object getResponse, Params params) throws IOException, ApiException {
								Object val = request.objectInput().opt(name);
								val = fieldMapping.unmarshal(val);
								try {
									return fieldMapping.type.cast(val, paramType);
								} catch (NullPointerException npe) {
									throw new ApiException("Field "+fieldMapping.parent+"."+name+" value is absent");
								}
							}
						};
					} else {
						if (bodyParamFound || endpoint.getBody() == null) {
							throw new ConfigurationException("Unrecognized parameter '"+name+"' in method "+this.method+": should be either of the type Params or be one of the declared parameters of the API call");
						}
						// try body parameter
						args[i] = checkBodyType(endpoint.getBody(), paramType);
						bodyParamFound = true;
					}
				} else {
					String[] enumValues = null;
					if (param.getValues() != null) {
						List<String> list = param.getValues().getValue();
						enumValues = list.toArray(new String[list.size()]);
					}
					final SimpleApiType paramApiType;
					try {
						Object defaultValue = param.getDefault() == null ? null : parseParam(param, param.getDefault());
						paramApiType = SimpleApiTypeImpl.create(param.getType(), defaultValue, enumValues);
					} catch (ApiException e) {
						throw new ConfigurationException("Cannot parse default param value "+param.getDefault()+": "+e.getMessage());
					}
					if (!paramApiType.check(paramType)) {
						throw new ConfigurationException("Wrong argument '"+name+"' type: expected "+paramApiType.getJavaName());
					}
					args[i] = new ArgHandler() {
						@Override
						public Object get(APIRequest request, Object getResponse, Params params) throws IOException, ApiException {
							return paramApiType.cast(params.get(name), paramType);
						}
					};
				}
			}

			if (endpoint.getResponse() != null) {
				resourceFactory.checkContentType(endpoint.getResponse(), this.method);
			}
		}

		private FieldMapping checkFieldAsArgument(String name, Type paramType) throws ConfigurationException {
			final ObjectApiTypeImpl objectType = getBodyApiType();

			if (objectType == null) return null;
			return objectType.checkFieldAsArgument(name, paramType);
		}

		private ObjectApiTypeImpl getBodyApiType() {
			final ObjectApiTypeImpl objectType;
			if (endpoint.getBody() == null || endpoint.getBody().getJson() == null && endpoint.getBody().getPatch() == null) {
				objectType = null;
			} else {
				JsonType jsonType = endpoint.getBody().getJson();
				if (jsonType != null) objectType = marshaller.getObjectType(jsonType.getType());
				else objectType = marshaller.getObjectType(endpoint.getBody().getPatch().getType());
			}
			return objectType;
		}

		private ArgHandler checkBodyType(ContentType contentType, Type type) throws ConfigurationException {
			Class expect = null;
			ArgHandler argHandler;
			if (contentType.getBinary() != null) {
				expect = InputStream.class;
				argHandler = new ArgHandler() {
					@Override
					public Object get(APIRequest request, Object getResponse, Params params) throws IOException, ApiException {
						return request.binaryInput();
					}
				};
			} else if (contentType.getText() != null) {
				expect = Reader.class;
				argHandler = new ArgHandler() {
					@Override
					public Object get(APIRequest request, Object getResponse, Params params) throws IOException, ApiException {
						return request.textInput();
					}
				};
			} else {
				if (contentType.getJson() != null) {
					final ApiType apiType = resourceFactory.getApiType(contentType);
					if (!apiType.check(type)) {
						throw new ConfigurationException("Body argument of " + this.method + " is expected to be "+apiType.getJavaName());
					}
					argHandler = new ArgHandler() {
						@Override
						public Object get(APIRequest request, Object getResponse, Params params) throws IOException, ApiException {
							return apiType.unmarshal(request.objectInput());
						}
					};
				} else if (contentType.getPatch() != null) {
					PatchType patchType = contentType.getPatch();
					expect = marshaller.getClassForModel(patchType.getType());
					if (type instanceof ParameterizedType) {
						ParameterizedType ptype = (ParameterizedType) type;
						if (ptype.getRawType() == Patch.class) {
							if (ptype.getActualTypeArguments()[0] == expect) {
								final ObjectApiTypeImpl objectType = marshaller.getObjectType(patchType.getType());
								return new ArgHandler() {
									@Override
									public Object get(APIRequest request, Object getResponse, Params params) throws IOException, ApiException {
										return objectType.unmarshalPatch(getResponse, request.objectInput());
									}
								};
							}
						}
					} else if (type == expect) {
						final ObjectApiTypeImpl objectType = marshaller.getObjectType(patchType.getType());
						return new ArgHandler() {
							@Override
							public Object get(APIRequest request, Object getResponse, Params params) throws IOException, ApiException {
								Patch patch = objectType.unmarshalPatch(getResponse, request.objectInput());
								return patch.getPatched();
							}
						};
					}
					throw new ConfigurationException("Body argument of " + this.method + " is expected to be either "+expect.getName()+" or Patch<" + expect.getName() + ">");
				} else {
					throw new ConfigurationException("Not a well-formed <body> tag");
				}
			}
			if (expect != null && type != expect) {
				throw new ConfigurationException("Body argument of "+this.method+" is expected to be "+expect);
			}
			return argHandler;
		}

		Resource invoke(APIRequest request, Object getResult) throws IOException, ApiException {
			return resourceFactory.createResourceFrom(invokeRaw(request, getResult), endpoint.getResponse());
		}

		Object invokeRaw(APIRequest request, Object getResult) throws IOException, ApiException {
			Map<String, String> pathParams = match(getPath(request));
			ParamsImpl params = new ParamsImpl();
			for (Parameter param: endpoint.getParameters().getParameter()) {
				String paramStringValue = pathParams.get(param.getName());
				if (paramStringValue == null) {
					paramStringValue = request.param(param.getName());
				}
				if (!StringUtils.isEmpty(paramStringValue) && param.isHttpsonly() && !request.https()) {
					throw new ApiException("Bad request. Parameter "+param.getName()+" can only be sent over HTTPS.");
				}
				params.put(param.getName(), parseParam(param, paramStringValue));
			}
			Object result;
			try {
				Object[] argValues = new Object[args.length];
				for (int i=0; i<args.length; i++) {
					argValues[i] = args[i].get(request, getResult, params);
				}
				result = method.invoke(service, argValues);
				return result;
			} catch (InvocationTargetException ite) {
				Throwable cause = ite.getCause();
				if (cause instanceof ApiException) {
					throw (ApiException)cause;
				}
				Error error = findError(cause);
				if (error != null) {
					JSONObject errJSON = (JSONObject) marshaller.getObjectType(error.getType()).marshal(cause);
					throw new ApiException(cause.getMessage(), errJSON).setHttpStatus(error.getStatus());
				}
				if (ite.getCause() instanceof RuntimeException) {
					throw (RuntimeException)ite.getCause();
				}
				throw new RuntimeException(ite.getCause());
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}

		}

		// find exception by its type
		private Error findError(Throwable cause) {
			for (Error err: endpoint.getErrors().getError()) {
				ObjectApiTypeImpl type = marshaller.getObjectType(err.getType());
				if (type.clz.isAssignableFrom(cause.getClass())) {
					return err;
				}
			}
			return null;
		}

		private Map<String,String> match(String path) {
			return pathMatcher.match(path);
		}

		public boolean matches(String path) {
			return pathMatcher.matches(path);
		}

		public ContentType getResponseContentType() {
			return endpoint.getResponse();
		}

		public boolean isPatch() {
			return patch;
		}
	}

	private List<String> parseList(String header) {
		ArrayList<String> parsed = new ArrayList<String>();
		for (String part: header.split(",")) {
			parsed.add(part.trim());
		}
		return parsed;
	}

	private DelegatingAPIRequest changeMethod(final APIRequest request, final String newMethod) {
		return new DelegatingAPIRequest(request) {
			@Override
			public String method() {
				return newMethod;
			}
		};
	}

	EndpointMapping findEndpoint(APIRequest request) throws IOException, ApiException {
		boolean pathFound = false;
		for (EndpointMapping endpoint: endpoints) {
			Map<String, String> pathParams = endpoint.match(getPath(request));
			if (pathParams == null) continue;
			pathFound = true;

			if (!request.method().equals(endpoint.httpMethod)) {
				continue;
			}

			return endpoint;

		}
		if (pathFound) {
			String allowMethods = getAllowedMethodsString(request);
			throw new ApiException("Method not allowed").setHttpStatus(405).addHeader("Allow", allowMethods);
		}
		throw new ApiException("File not found").setHttpStatus(404);
	}

	private String getPath(APIRequest request) {
		return request.path().substring(pathPrefix.length());
	}

	private String getAllowedMethodsString(APIRequest request) throws IOException, ApiException {
		List<String> methods = getAllowedMethods(request);
		methods.add("OPTIONS");
		return StringUtils.join(methods, ", ");
	}

	@Override
	public List<String> getAllowedMethods(APIRequest request) throws IOException, ApiException {
		List<String> methods = new ArrayList<String>();
		for (EndpointMapping endpoint: endpoints) {
			if (endpoint.matches(getPath(request))) {
				methods.add(endpoint.httpMethod);
			}
		}
		return methods;
	}

	static private final Pattern ISDOUBLE = Pattern.compile("[\\.eE]");

	Object parseParam(Parameter param, String valueStr) throws ApiException {
		if (valueStr == null) {
			if (!param.isOptional()) {
				throw new ApiException("Absent parameter "+param.getName());
			}
			valueStr = param.getDefault();
		}

		if (valueStr == null) return null;
		switch (param.getType()) {
		case BOOLEAN:
			if (valueStr.equals("true") || valueStr.equals("yes") || valueStr.equals("on") || valueStr.equals("1")) {
				return Boolean.TRUE;
			}
			if (valueStr.equals("false") || valueStr.equals("no") || valueStr.equals("off") || valueStr.equals("0")) {
				return Boolean.FALSE;
			}
			throw new ApiException("Wrong parameter '"+param.getName()+"' value: expected one of 'true', 'false', 'yes', 'no', 'on', 'off', '1', '0'");
		case NUMBER:
			valueStr = valueStr.trim();
			try {
				if (ISDOUBLE.matcher(valueStr).find()) {
					return Double.parseDouble(valueStr);
				} else {
					return Long.parseLong(valueStr);
				}
			} catch (NumberFormatException ex) {
				throw new ApiException("Wrong numeric parameter '"+param.getName()+"' value: not a number or a number out of range");
			}
		case STRING:
			if (param.getValues() != null) {
				// check allowed values
				if (!param.getValues().getValue().contains(valueStr)) {
					throw new ApiException("Wrong parameter '"+param.getName()+"' value: expected one of "+
						StringUtils.join(param.getValues().getValue(), ", "));
				}
			}
			return valueStr;
		}
		throw new AssertionError();
	}

}
