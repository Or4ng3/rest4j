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

import com.rest4j.ApiException;
import com.rest4j.ConfigurationException;
import com.rest4j.ObjectFactoryChain;
import com.rest4j.impl.model.FieldType;
import com.rest4j.impl.model.Model;
import com.rest4j.type.ApiType;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @author Joseph Kapizza <joseph@rest4j.com>
 */
public class Marshaller {
	Map<String, ObjectApiTypeImpl> models = new HashMap<String, ObjectApiTypeImpl>();
	ObjectFactoryChain chain = new ObjectFactoryChain() {
		@Nullable
		@Override
		public Object createInstance(@Nonnull String modelName, @Nonnull Class clz, @Nonnull JSONObject object) {
			Object inst;
			try {
				inst = clz.newInstance();
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				// we have already checked instance creation in the configuration phase, so this should work
				throw new AssertionError(e);
			}
			return inst;
		}

		@Nullable
		@Override
		public Object replaceModel(@Nonnull String modelName, @Nonnull Class clz, @Nullable Object object) throws ApiException {
			return object;
		}
	};

	public static class ModelConfig {
		Model model;
		Object customMapper;

		public ModelConfig(Model model, Object customMapping) {
			this.model = model;
			this.customMapper = customMapping;
		}
	}

	Marshaller(List<ModelConfig> modelConfigs) throws ConfigurationException {
		this(modelConfigs, new com.rest4j.ObjectFactory[0]);
	}

	Marshaller(List<ModelConfig> modelConfigs, com.rest4j.ObjectFactory factory) throws ConfigurationException {
		this(modelConfigs, new com.rest4j.ObjectFactory[]{factory});
	}

	Marshaller(List<ModelConfig> modelConfigs, com.rest4j.ObjectFactory[] factories) throws ConfigurationException {
		for (com.rest4j.ObjectFactory of: factories) {
			addObjectFactory(of);
		}
		for (ModelConfig modelConfig : modelConfigs) {
			Model model = modelConfig.model;
			Object customMapper = modelConfig.customMapper;
			Class clz;
			try {
				clz = Class.forName(model.getClazz());
			} catch (ClassNotFoundException e) {
				throw new ConfigurationException("Cannot find class " + model.getClazz());
			}

			models.put(model.getName(), new ObjectApiTypeImpl(this, model.getName(), clz, model, customMapper, chain));
		}

		// fill model interconnections and type-check
		for (ModelConfig modelConfig : modelConfigs) {
			Model model = modelConfig.model;
			ObjectApiTypeImpl modelImpl = models.get(model.getName());
			modelImpl.link();
		}
	}

	public ObjectApiTypeImpl getObjectType(String model) {
		return models.get(model);
	}

	public ApiType getArrayType(String model) {
		return new ArrayApiTypeImpl(models.get(model));
	}

	public ApiType getMapType(String model) {
		return new MapApiTypeImpl(models.get(model));
	}

	public Class getClassForModel(String model) {
		return models.get(model).clz;
	}

	static private final Pattern ISDOUBLE = Pattern.compile("[\\.eE]");

	Object parse(String val, FieldType type) {
		if (val == null) return null;
		switch (type) {
			case BOOLEAN: return Boolean.parseBoolean(val);
			case NUMBER:
				if (ISDOUBLE.matcher(val).find()) {
					return Double.parseDouble(val);
				} else {
					return Long.parseLong(val);
				}
			case STRING: return val;
		}
		throw new AssertionError();
	}

	static void checkEnum(Class clz, String[] enumValues) throws ConfigurationException {
		// check that the declared enum values are a subset of java enum
		for (String declaredValue: enumValues) {
			boolean found = false;
			for (Object option: clz.getEnumConstants()) {
				if (option.toString().equals(declaredValue)) found = true;
			}
			if (!found) {
				throw new ConfigurationException("Cannot find a enum value "+declaredValue+" in "+clz);
			}
		}
	}

	private void addObjectFactory(final com.rest4j.ObjectFactory factory) {
		final ObjectFactoryChain nextChain = chain;
		chain = new ObjectFactoryChain() {

			@Nullable
			@Override
			public Object createInstance(@Nonnull String modelName, @Nonnull Class clz, @Nonnull JSONObject object) throws JSONException, ApiException {
				return factory.createInstance(modelName, clz, object, nextChain);
			}

			@Nullable
			@Override
			public Object replaceModel(@Nonnull String modelName, @Nonnull Class clz, @Nullable Object object) throws ApiException {
				return factory.replaceModel(modelName, clz, object, nextChain);
			}
		};
	}

}
