/*
 * Lincheck - Linearizability checker
 *
 * Copyright (C) 2015-2022 Devexperts, LLC
 * Copyright (C) 2023 Devexperts Ireland Limited
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.devexperts.dxlab.lincheck;

import com.devexperts.dxlab.lincheck.annotations.OpGroupConfig;
import com.devexperts.dxlab.lincheck.annotations.Operation;
import com.devexperts.dxlab.lincheck.annotations.Param;
import com.devexperts.dxlab.lincheck.execution.ActorGenerator;
import com.devexperts.dxlab.lincheck.paramgen.ByteGen;
import com.devexperts.dxlab.lincheck.paramgen.DoubleGen;
import com.devexperts.dxlab.lincheck.paramgen.FloatGen;
import com.devexperts.dxlab.lincheck.paramgen.IntGen;
import com.devexperts.dxlab.lincheck.paramgen.LongGen;
import com.devexperts.dxlab.lincheck.paramgen.ParameterGenerator;
import com.devexperts.dxlab.lincheck.paramgen.ShortGen;
import com.devexperts.dxlab.lincheck.paramgen.StringGen;
import com.devexperts.dxlab.lincheck.strategy.stress.StressCTest;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Contains information about the provided operations (see {@link Operation}).
 * Several {@link StressCTest tests} can refer to one structure
 * (i.e. one test class could have several {@link StressCTest} annotations)
 */
public class CTestStructure {
    public final List<ActorGenerator> actorGenerators;
    public final List<OperationGroup> operationGroups;

    private CTestStructure(List<ActorGenerator> actorGenerators, List<OperationGroup> operationGroups) {
        this.actorGenerators = actorGenerators;
        this.operationGroups = operationGroups;
    }

    /**
     * Constructs {@link CTestStructure} for the specified test class.
     */
    public static CTestStructure getFromTestClass(Class<?> testClass) {
        // Read named parameter paramgen (declared for class)
        Map<String, ParameterGenerator<?>> namedGens = new HashMap<>();
        for (Param paramAnn : testClass.getAnnotationsByType(Param.class)) {
            if (paramAnn.name().isEmpty()) {
                throw new IllegalArgumentException("@Param name in class declaration cannot be empty");
            }
            namedGens.put(paramAnn.name(), createGenerator(paramAnn));
        }
        // Read group configurations
        Map<String, OperationGroup> groupConfigs = new HashMap<>();
        for (OpGroupConfig opGroupConfigAnn: testClass.getAnnotationsByType(OpGroupConfig.class)) {
            groupConfigs.put(opGroupConfigAnn.name(), new OperationGroup(opGroupConfigAnn.name(),
                opGroupConfigAnn.nonParallel()));
        }
        // Create actor paramgen
        List<ActorGenerator> actorGenerators = new ArrayList<>();
        for (Method m : testClass.getDeclaredMethods()) {
            // Operation
            if (m.isAnnotationPresent(Operation.class)) {
                Operation operationAnn = m.getAnnotation(Operation.class);
                // Check that params() in @Operation is empty or has the same size as the method
                if (operationAnn.params().length > 0 && operationAnn.params().length != m.getParameterCount()) {
                    throw new IllegalArgumentException("Invalid count of paramgen for " + m.toString()
                        + " method in @Operation");
                }
                // Construct list of parameter paramgen
                final List<ParameterGenerator<?>> gens = new ArrayList<>();
                for (int i = 0; i < m.getParameterCount(); i++) {
                    String nameInOperation = operationAnn.params().length > 0 ? operationAnn.params()[i] : null;
                    gens.add(getOrCreateGenerator(m, m.getParameters()[i], nameInOperation, namedGens));
                }
                // Get list of handled exceptions if they are presented
                List<Class<? extends Throwable>> handledExceptions = Arrays.asList(operationAnn.handleExceptionsAsResult());
                ActorGenerator actorGenerator = new ActorGenerator(m, gens, handledExceptions, operationAnn.runOnce());
                actorGenerators.add(actorGenerator);
                // Get list of groups and add this operation to specified ones
                String opGroup = operationAnn.group();
                if (!opGroup.isEmpty()) {
                    OperationGroup operationGroup = groupConfigs.get(opGroup);
                    if (operationGroup == null)
                        throw new IllegalStateException("Operation group " + opGroup + " is not configured");
                    operationGroup.actors.add(actorGenerator);
                }
            }
        }
        // Create StressCTest class configuration
        return new CTestStructure(actorGenerators, new ArrayList<>(groupConfigs.values()));
    }

    private static ParameterGenerator<?> getOrCreateGenerator(Method m, Parameter p, String nameInOperation,
        Map<String, ParameterGenerator<?>> namedGens)
    {
        // Read @Param annotation on the parameter
        Param paramAnn = p.getAnnotation(Param.class);
        // If this annotation not presented use named generator based on name presented in @Operation or parameter name.
        if (paramAnn == null) {
            // If name in @Operation is presented, return the generator with this name,
            // otherwise return generator with parameter's name
            String name = nameInOperation != null ? nameInOperation :
                (p.isNamePresent() ? p.getName() : null);
            if (name != null)
                return checkAndGetNamedGenerator(namedGens, name);
            // Parameter generator is not specified, try to create a default one
            ParameterGenerator<?> defaultGenerator = createDefaultGenerator(p);
            if (defaultGenerator != null)
                return defaultGenerator;
            // Cannot create default parameter generator, throw an exception
            throw new IllegalStateException("Generator for parameter \'" + p + "\" in method \""
                + m.getName() + "\" should be specified.");
        }
        // If the @Param annotation is presented check it's correctness firstly
        if (!paramAnn.name().isEmpty() && !(paramAnn.gen() == ParameterGenerator.Dummy.class))
            throw new IllegalStateException("@Param should have either name or gen with optionally configuration");
        // If @Param annotation specifies generator's name then return the specified generator
        if (!paramAnn.name().isEmpty())
            return checkAndGetNamedGenerator(namedGens, paramAnn.name());
        // Otherwise create new parameter generator
        return createGenerator(paramAnn);
    }

    private static ParameterGenerator<?> createGenerator(Param paramAnn) {
        try {
            return paramAnn.gen().getConstructor(String.class).newInstance(paramAnn.conf());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create parameter gen", e);
        }
    }

    private static ParameterGenerator<?> createDefaultGenerator(Parameter p) {
        Class<?> t = p.getType();
        if (t == byte.class   || t == Byte.class)    return new ByteGen("");
        if (t == short.class  || t == Short.class)   return new ShortGen("");
        if (t == int.class    || t == Integer.class) return new IntGen("");
        if (t == long.class   || t == Long.class)    return new LongGen("");
        if (t == float.class  || t == Float.class)   return new FloatGen("");
        if (t == double.class || t == Double.class)  return new DoubleGen("");
        if (t == String.class) return new StringGen("");
        return null;
    }

    private static ParameterGenerator<?> checkAndGetNamedGenerator(Map<String, ParameterGenerator<?>> namedGens, String name) {
        return Objects.requireNonNull(namedGens.get(name), "Unknown generator name: \"" + name + "\"");
    }

    public static class OperationGroup {
        public final String name;
        public final boolean nonParallel;
        public final List<ActorGenerator> actors;

        public OperationGroup(String name, boolean nonParallel) {
            this.name = name;
            this.nonParallel = nonParallel;
            this.actors = new ArrayList<>();
        }

        @Override
        public String toString() {
            return "OperationGroup{" +
                "name='" + name + '\'' +
                ", nonParallel=" + nonParallel +
                ", actors=" + actors +
                '}';
        }
    }
}
