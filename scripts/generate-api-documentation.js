#!/usr/bin/env node
const childProcess = require("child_process");
const process = require("process");
const yargs = require("yargs");
const fs = require("fs");
const path = require("path");
const url = require("url");
const { toSwagger: apidocToSwagger } = require("./apidocToSwagger");

const argv = yargs
    .options({
        input: {
            description: "Input root folder",
            type: "string",
            default: path.dirname(__dirname)
        },
        output: {
            description: "Where to write output.",
            type: "string",
            demand: true
        },
        config: {
            description: "Configuration directory.",
            type: "string",
            demand: true
        },
        include: {
            description: "Regex for which components to include",
            type: "string",
            default: "^magda-.*-api$"
        }
    })
    .help().argv;

// Run APIDOCjs

argv.include = new RegExp(argv.include);

const input = fs
    .readdirSync(argv.input)
    .filter(x => x.match(argv.include))
    .map(x => `-i ${path.join(argv.input, x, "src")}`)
    .join(" ");

const output = `-o ${argv.output}`;

// --- apidoc require directory name of the config file and doesn't accept config file name
const config = `-c ${argv.config}`;

const apidocPath = path.resolve(
    path.dirname(__dirname),
    "./node_modules/.bin/apidoc"
);

childProcess.execSync(`${apidocPath} ${input} ${output} ${config}`);

// Convert APIDOC to swagger and openapi specs

function makeMeta(apiProject, apiData, flavor) {
    const title = apiProject.name;
    const version = require("./package.json").version;
    const description = apiProject.description;
    const info = {
        title,
        version,
        description
    };

    if (flavor === "swagger") {
        const apiUrl = url.parse(apiProject.url);
        return {
            swagger: "2.0",
            info,
            schemes: [apiUrl.protocol.replace(/[:]/, "")],
            host: apiUrl.host,
            basePath: apiUrl.path
        };
    } else {
        return {
            openapi: "3.0.1",
            info,
            servers: [
                {
                    url: apiProject.url
                }
            ]
        };
    }
}

function makePaths(apiProject, apiData, flavor) {
    const paths = {};

    for (const apiCall of apiData) {
        const { url, type } = apiCall;
        paths[url] = paths[url] || {};
        paths[url][type] = makePathCall(apiCall, flavor);
    }
    return { paths };
}

function makePathCall(apiCall, flavor) {
    const { group, title, description } = apiCall;
    const summary = title;
    const tags = [group];

    const parameters = [];
    const bodyParameters = {
        type: "object",
        properties: {}
    };
    let requestBody = undefined;

    if (apiCall.parameter && apiCall.parameter.fields) {
        for (const [type, params] of Object.entries(apiCall.parameter.fields)) {
            for (const param of params) {
                if (param.group.match(/body/gi)) {
                    bodyParameters.properties[param.field] = {
                        type: param.type,
                        description: param.description
                    };
                    if (!param.optional) {
                        bodyParameters.required = bodyParameters.required || [];
                        bodyParameters.required.push(param.field);
                    }
                } else {
                    parameters.push({
                        in: "query",
                        name: param.field,
                        description: param.description,
                        required: !param.optional,
                        schema: {
                            type: param.type
                        }
                    });
                }
            }

            if (Object.keys(bodyParameters.properties).length > 0) {
                if (flavor === "swagger") {
                    parameters.push({
                        in: "body",
                        name: "body",
                        description: "Request body",
                        required: true,
                        schema: bodyParameters
                    });
                } else {
                    requestBody = {
                        content: {
                            "application/json": {
                                schema: bodyParameters
                            }
                        }
                    };
                }
            }
        }
    }

    const responses = {};

    if (apiCall.success && apiCall.success.examples) {
        for (const example of apiCall.success.examples) {
            responses[example.title] = {
                description: `<pre>${example.content}<pre>`
            };
        }
    }

    if (apiCall.error && apiCall.error.examples) {
        for (const example of apiCall.error.examples) {
            responses[example.title] = {
                description: `<pre>${example.content}<pre>`
            };
        }
    }

    const pathData = {
        tags,
        summary,
        description,
        parameters,
        requestBody,
        responses
    };

    if (flavor === "swagger") {
        pathData["operationId"] = apiCall.name;
    }

    return pathData;
}

const apiProject = JSON.parse(
    fs.readFileSync(path.join(argv.output, "api_project.json"))
);
const apiData = JSON.parse(
    fs.readFileSync(path.join(argv.output, "api_data.json"))
);

const swaggerJson = apidocToSwagger(apiData, apiProject);

fs.writeFileSync(
    path.join(argv.output, "swagger.json"),
    JSON.stringify(swaggerJson, null, 2)
);

fs.writeFileSync(
    path.join(argv.output, "openapi.json"),
    JSON.stringify(
        Object.assign(
            makeMeta(apiProject, apiData, "openapi"),
            makePaths(apiProject, apiData, "openapi")
        ),
        null,
        2
    )
);