package org.hypergraphql;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.jena.ext.com.google.common.base.Predicate;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.log4j.Logger;

/**
 * Created by szymon on 22/08/2017.
 */
public class SparqlClientExt extends SparqlClient {


    public SparqlClientExt(List<Map<String, String>> queryRequests, Config config) {

        super(queryRequests, config);

    }


    @Override
    public List<RDFNode> getValuesOfObjectProperty(String subject, String predicate, Map<String, Object> args) {


        Resource resource = this.model.getResource(subject);
        Property predicateRes = this.model.getProperty(predicate);
        NodeIterator iterator = this.model.listObjectsOfProperty(resource, predicateRes);
        List<RDFNode> uriList = new ArrayList<>();


        while (iterator.hasNext()) {

            RDFNode next = iterator.next();

            if (!next.isLiteral()) uriList.add(next);
        }

        return uriList;

    }

    @Override
    public RDFNode getValueOfObjectProperty(String subject, String predicate, Map<String, Object> args) {


        Resource resource = this.model.getResource(subject);
        Property predicateRes = this.model.getProperty(predicate);
        NodeIterator iterator = this.model.listObjectsOfProperty(resource, predicateRes);
        while (iterator.hasNext()) {

            RDFNode next = iterator.next();

            if (!next.isLiteral()) return next;
        }
        return null;

    }

    @Override
    public List<Object> getValuesOfDataProperty(String subject, String predicate, Map<String, Object> args) {

        List<Object> valList = new ArrayList<>();


        Resource resource = this.model.getResource(subject);
        Property predicateRes = this.model.getProperty(predicate);
        NodeIterator iterator = this.model.listObjectsOfProperty(resource, predicateRes);

        while (iterator.hasNext()) {


            RDFNode data = iterator.next();


            if (data.isLiteral()) {
                if (args.containsKey("lang")) {
                    if (data.asLiteral().getLanguage().toString().equals(args.get("lang").toString()))
                        valList.add(data.asLiteral().getString());
                } else {

                    valList.add(data.asLiteral().getString());

                }
            }
        }

        return valList;


    }


    @Override
    public String getValueOfDataProperty(String subject, String predicate, Map<String, Object> args) {


        Resource resource = this.model.getResource(subject);
        Property predicateRes = this.model.getProperty(predicate);
        NodeIterator iterator = this.model.listObjectsOfProperty(resource, predicateRes);


        while (iterator.hasNext()) {


            RDFNode data = iterator.next();


            if (data.isLiteral()) {

                if (args.containsKey("lang"))

                {
                    if (data.asLiteral().getLanguage().toString().equals(args.get("lang").toString()))
                        return data.asLiteral().getString();
                } else {

                    return data.asLiteral().getString();
                }

            }

        }


        return null;
    }

    @Override
    public List<RDFNode> getSubjectsOfObjectPropertyFilter(String predicate, String uri) {
        Resource resource = this.model.getResource(uri);
        Property predicateRes = this.model.getProperty(predicate);
        ResIterator iterator = this.model.listSubjectsWithProperty(predicateRes, resource);
        List<RDFNode> nodeList = new ArrayList<>();


        while (iterator.hasNext()) {

            nodeList.add(iterator.next());
        }

        return nodeList;

    }

}