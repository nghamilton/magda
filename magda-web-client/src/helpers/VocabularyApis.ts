import { config } from "../config";
import fetch from "isomorphic-fetch";
import uniq from "lodash/uniq";
import flatMap from "lodash/flatMap";
const apiEndpoints = config.vocabularyApiEndpoints;

async function queryEndpoint(
    apiEndpoint: string,
    str: string
): Promise<string[]> {
    if (!apiEndpoint) {
        throw new Error(
            "Failed to contact Vocabulary API: API endpoint cannot be empty!"
        );
    }

    const requestUrl =
        `${apiEndpoint}?labelcontains=` + encodeURIComponent(str);

    const data = await fetch(requestUrl).then(response => {
        if (response.status === 200) {
            return response.json();
        }
        throw new Error(response.statusText);
    });

    if (!data || !data.result || !Array.isArray(data.result.items)) {
        throw new Error(
            "Failed to contact Vocabulary API: Invalid API response!"
        );
    }

    const keywords: string[] = [];

    data.result.items.forEach(item => {
        let prefLabels: {
            _value: string;
            _lang: string;
        }[] = [];

        if (item.broader && item.broader.prefLabel) {
            if (!Array.isArray(item.broader.prefLabel)) {
                prefLabels.push(item.broader.prefLabel);
            } else {
                prefLabels = prefLabels.concat(item.broader.prefLabel);
            }
        }

        if (item.prefLabel) {
            if (!Array.isArray(item.prefLabel)) {
                prefLabels.push(item.prefLabel);
            } else {
                prefLabels = prefLabels.concat(item.prefLabel);
            }
        }

        prefLabels.forEach(label => {
            if (label._lang && label._lang !== "en") {
                return;
            }
            keywords.push(label._value);
        });
    });
    return keywords;
}

export async function query(str: string): Promise<string[]> {
    if (!Array.isArray(apiEndpoints) || !apiEndpoints.length) {
        throw new Error(
            "Failed to contact Vocabulary API: invalid vocabularyApiEndpoints config!"
        );
    }
    const result = await Promise.all(
        apiEndpoints.map(api => queryEndpoint(api, str))
    );
    const keywords = uniq(flatMap(result));
    return keywords;
}

export async function isValidKeyword(keyword: string): Promise<boolean> {
    const keywords = await query(keyword);
    if (!Array.isArray(keywords) || !keywords.length) return false;
    return true;
}
