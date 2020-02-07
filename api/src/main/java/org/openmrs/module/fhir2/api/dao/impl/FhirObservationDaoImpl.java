/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.fhir2.api.dao.impl;

import static org.hibernate.criterion.Projections.property;
import static org.hibernate.criterion.Restrictions.and;
import static org.hibernate.criterion.Restrictions.eq;
import static org.hibernate.criterion.Restrictions.ilike;
import static org.hibernate.criterion.Restrictions.in;
import static org.hibernate.criterion.Restrictions.or;
import static org.hibernate.criterion.Subqueries.propertyEq;

import javax.inject.Inject;
import javax.inject.Named;
import javax.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.hibernate.Criteria;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.MatchMode;
import org.hibernate.internal.CriteriaImpl;
import org.hl7.fhir.r4.model.Patient;
import org.openmrs.Obs;
import org.openmrs.module.fhir2.FhirConceptSource;
import org.openmrs.module.fhir2.api.dao.FhirObservationDao;
import org.springframework.stereotype.Component;

@Component
public class FhirObservationDaoImpl extends BaseDaoImpl implements FhirObservationDao {

	@Inject
	@Named("sessionFactory")
	private SessionFactory sessionFactory;

	@Override
	public Obs getObsByUuid(String uuid) {
		return (Obs) sessionFactory.getCurrentSession().createCriteria(Obs.class).add(eq("uuid", uuid)).uniqueResult();
	}

	@Override
	public Collection<Obs> searchForObservations(ReferenceParam encounterReference, ReferenceParam patientReference,
			TokenAndListParam code, SortSpec sort) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(Obs.class);

		handleEncounterReference("e", encounterReference).ifPresent(c -> criteria.createAlias("encounter", "e").add(c));
		handlePatientReference(criteria, patientReference);
		handleCodedConcept(criteria, code);
		handleSort(criteria, sort, this::paramToProp);

		return criteria.list();
	}

	private void handleCodedConcept(Criteria criteria, TokenAndListParam code) {
		if (code != null) {
			criteria.createAlias("concept", "c");

			handleAndListParam(code,
					(TokenOrListParamHandler) tokenList -> handleOrListParamBySystem(tokenList, (system, tokens) -> {
						if (system.isEmpty()) {
							return Optional.of(or(in("c.conceptId",
									tokens.stream().map(TokenParam::getValue).map(NumberUtils::toInt).collect(
											Collectors.toList())),
									in("c.uuid", tokens.stream().map(TokenParam::getValue).collect(
											Collectors.toList()))));
						} else {
							asImpl(criteria).ifPresent(c -> {
								if (!containsAlias(c, "cm")) {
									criteria.createAlias("c.conceptMappings", "cm")
											.createAlias("cm.conceptReferenceTerm", "crt");
								}
							});

							return Optional.of(generateSystemQuery(system, tokens.stream().map(TokenParam::getValue).collect(
									Collectors.toList())));
						}
					})).ifPresent(criteria::add);
		}
	}

	private Criterion generateSystemQuery(String system, List<String> codes) {
		DetachedCriteria conceptSourceCriteria = DetachedCriteria.forClass(FhirConceptSource.class).add(eq("url", system))
				.setProjection(property("conceptSource"));

		if (codes.size() > 1) {
			return and(propertyEq("crt.conceptSource", conceptSourceCriteria), in("crt.code", codes));
		} else {
			return and(propertyEq("crt.conceptSource", conceptSourceCriteria), eq("crt.code", codes.get(0)));
		}
	}

	private String paramToProp(@NotNull String paramName) {
		switch (paramName) {
			case "date":
				return "obsDatetime";
			default:
				return null;
		}
	}
}
