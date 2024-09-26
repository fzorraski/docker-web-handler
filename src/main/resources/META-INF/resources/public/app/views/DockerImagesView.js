import {actionGetDockerImages, actionRemoveDockerImage} from "../controller/DockerImageController.js";

function showDockerImages(repository, tag, imageId, created, size) {

    const line = document.createElement('tr')

    line.innerHTML = `

        <td><b>${repository}</b></td>
        <td>${tag}</td>
        <td>${imageId}</td>
        <td>${created}</td>
        <td>${size}</td>
    `;
    line.appendChild(createButton('remove', imageId));
    return line;
}

function createButton(label, id) {

    let htmlButtonElement = document.createElement("button");
    let textNode = document.createTextNode(label);

    htmlButtonElement.id = "btn-i-" + label;

    htmlButtonElement.setAttribute('style', 'margin: 3px');
    htmlButtonElement.setAttribute('class', 'btn btn-danger');

    htmlButtonElement.appendChild(textNode);

    htmlButtonElement.addEventListener("click", () => {
        actionRemoveDockerImage(id)
    })

    return htmlButtonElement;
}

let bodyTable = document.querySelector('#dockerImagesTable');

actionGetDockerImages()
    .then(showImages => {
        showImages.forEach(index => {
            bodyTable.appendChild(showDockerImages(index.repository,
                index.tag,
                index.imageId,
                index.created,
                index.size
            ))
        })
    })


export function showLoader() {
    // let s = '<div class="spinner-border"></div>';
    $('.table-responsive').append('<div class="loader" id="di-loader">' + '</div>');
    document.getElementById('dockerImagesTable').style.pointerEvents = "none";
}

export function stopLoader() {
    document.getElementById('di-loader').style.display = "none";
    document.getElementById('dockerImagesTable').style.pointerEvents = "initial";
}
